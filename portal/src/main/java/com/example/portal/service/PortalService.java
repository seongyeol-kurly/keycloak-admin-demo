package com.example.portal.service;

import com.example.portal.domain.AccessRequest;
import com.example.portal.domain.AuditLog;
import com.example.portal.domain.ManagedClient;
import com.example.portal.domain.Repositories.AccessRequestRepository;
import com.example.portal.domain.Repositories.AuditLogRepository;
import com.example.portal.domain.Repositories.ManagedClientRepository;
import com.example.portal.keycloak.KeycloakAdminClient;
import com.example.portal.security.Me;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 포털 비즈니스 로직 + 권한 규칙(가드레일) + 감사 로그.
 *
 * 권한 규칙 (PoC)
 *  - client(어드민) 등록: 로그인한 누구나 → 등록자가 소유자
 *  - client role 생성/삭제, 사용자·그룹에 role 부여/회수, secret 재발급: 소유자 또는 portal-admin
 *  - 그룹 생성, 그룹 멤버 추가/제거: 로그인한 누구나 (운영 시 그룹 관리자 개념 필요)
 *  - 권한 신청 승인/반려: 신청자 본인(셀프 승인, PoC) / 소유자 / portal-admin
 *  - 포털이 등록·관리하는 client의 role만 다룸. Keycloak 내장 client(realm-management 등)는 접근 불가 → 권한 상승 방지
 */
@Service
public class PortalService {

    private static final Pattern CLIENT_ID = Pattern.compile("^[a-z][a-z0-9-]{2,39}$");
    private static final Pattern ROLE_NAME = Pattern.compile("^[a-z][a-z0-9._-]{1,49}$");

    private final KeycloakAdminClient kc;
    private final ManagedClientRepository clients;
    private final AccessRequestRepository requests;
    private final AuditLogRepository audits;
    private final Set<String> protectedClients;

    public PortalService(KeycloakAdminClient kc, ManagedClientRepository clients, AccessRequestRepository requests,
                         AuditLogRepository audits, @Value("${app.protected-clients}") List<String> protectedClients) {
        this.kc = kc;
        this.clients = clients;
        this.requests = requests;
        this.audits = audits;
        this.protectedClients = new HashSet<>(protectedClients);
    }

    // ================================================================ 조회/권한

    public List<ManagedClient> clients() { return clients.findAllByOrderByClientIdAsc(); }

    public ManagedClient client(String clientId) {
        return clients.findById(clientId).orElseThrow(() -> new PortalException("포털에 등록되지 않은 어드민이에요: " + clientId));
    }

    public boolean canManage(Me me, ManagedClient c) {
        return me.portalAdmin() || (c.owner != null && c.owner.equals(me.username()));
    }

    private void requireManage(Me me, ManagedClient c) {
        if (!canManage(me, c)) {
            throw new PortalException("'" + c.clientId + "' 어드민의 소유자(" + (c.owner == null || c.owner.isBlank() ? "없음 → portal-admin만" : c.owner) + ")만 변경할 수 있어요.");
        }
    }

    public List<String> roleNames(ManagedClient c) {
        return kc.clientRoles(c.keycloakId).stream().map(r -> (String) r.get("name")).sorted().toList();
    }

    private Map<String, Object> role(ManagedClient c, String role) { return kc.clientRole(c.keycloakId, role); }

    private Map<String, Object> userByUsername(String username) {
        return kc.userByUsername(username.trim()).orElseThrow(() -> new PortalException("사용자를 찾을 수 없어요: " + username));
    }

    // ================================================================ 어드민(client) 등록/관리

    public record Registered(ManagedClient client, String secret) {}

    public Registered registerClient(Me me, String clientId, String name, String baseUrl) {
        clientId = clientId == null ? "" : clientId.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!CLIENT_ID.matcher(clientId).matches()) throw new PortalException("client ID는 영문 소문자로 시작하는 3~40자 (소문자, 숫자, -)만 가능해요.");
        if (protectedClients.contains(clientId)) throw new PortalException("사용할 수 없는 client ID예요: " + clientId);
        if (!baseUrl.matches("^https?://[^\\s/]+(:\\d+)?(/[^\\s]*)?$")) throw new PortalException("어드민 주소는 http(s)://host[:port] 형식이어야 해요.");
        if (clients.existsById(clientId) || kc.clientByClientId(clientId).isPresent()) throw new PortalException("이미 존재하는 client ID예요: " + clientId);

        String id = kc.createClient(clientId, name, baseUrl, me.username());
        String secret = kc.clientSecret(id);
        ManagedClient mc = clients.save(new ManagedClient(clientId, id, name, baseUrl, me.username()));
        audit(me, "CLIENT_REGISTER", clientId, "redirect=" + baseUrl + "/*, owner=" + me.username());
        return new Registered(mc, secret);
    }

    public String regenerateSecret(Me me, String clientId) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        String s = kc.regenerateSecret(c.keycloakId);
        audit(me, "CLIENT_SECRET_REGENERATE", clientId, "");
        return s;
    }

    public void changeOwner(Me me, String clientId, String newOwner) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        userByUsername(newOwner);
        String before = c.owner;
        c.owner = newOwner.trim();
        clients.save(c);
        audit(me, "CLIENT_OWNER_CHANGE", clientId, before + " → " + c.owner);
    }

    // ================================================================ client role

    public void createRole(Me me, String clientId, String role, String description) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        role = role == null ? "" : role.trim();
        if (!ROLE_NAME.matcher(role).matches()) throw new PortalException("role 이름은 영문 소문자로 시작하는 2~50자 (소문자, 숫자, . _ -)만 가능해요.");
        kc.createClientRole(c.keycloakId, role, description);
        audit(me, "ROLE_CREATE", clientId + "/" + role, description);
    }

    public void deleteRole(Me me, String clientId, String role) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        kc.deleteClientRole(c.keycloakId, role);
        audit(me, "ROLE_DELETE", clientId + "/" + role, "");
    }

    public void assignRoleToUser(Me me, String clientId, String role, String username) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        Map<String, Object> u = userByUsername(username);
        kc.assignUserClientRole((String) u.get("id"), c.keycloakId, role(c, role));
        audit(me, "USER_ROLE_ASSIGN", clientId + "/" + role, "user=" + u.get("username"));
    }

    public void unassignRoleFromUser(Me me, String clientId, String role, String userId) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        kc.unassignUserClientRole(userId, c.keycloakId, role(c, role));
        audit(me, "USER_ROLE_UNASSIGN", clientId + "/" + role, "user=" + username(userId));
    }

    public void assignRoleToGroup(Me me, String clientId, String role, String groupId) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        kc.assignGroupClientRole(groupId, c.keycloakId, role(c, role));
        audit(me, "GROUP_ROLE_ASSIGN", clientId + "/" + role, "group=" + groupName(groupId));
    }

    public void unassignRoleFromGroup(Me me, String clientId, String role, String groupId) {
        ManagedClient c = client(clientId);
        requireManage(me, c);
        kc.unassignGroupClientRole(groupId, c.keycloakId, role(c, role));
        audit(me, "GROUP_ROLE_UNASSIGN", clientId + "/" + role, "group=" + groupName(groupId));
    }

    // ================================================================ 그룹

    public String createGroup(Me me, String name) {
        if (name == null || name.isBlank()) throw new PortalException("그룹 이름을 입력해주세요.");
        String id = kc.createGroup(name.trim());
        audit(me, "GROUP_CREATE", name.trim(), "");
        return id;
    }

    public void joinGroup(Me me, String groupId, String username) {
        Map<String, Object> u = userByUsername(username);
        kc.joinGroup((String) u.get("id"), groupId);
        audit(me, "GROUP_MEMBER_ADD", groupName(groupId), "user=" + u.get("username"));
    }

    public void leaveGroup(Me me, String groupId, String userId) {
        kc.leaveGroup(userId, groupId);
        audit(me, "GROUP_MEMBER_REMOVE", groupName(groupId), "user=" + username(userId));
    }

    // ================================================================ 권한 신청 / 결재

    public AccessRequest submitRequest(Me me, String clientId, String role, String reason) {
        ManagedClient c = client(clientId);
        role(c, role); // 존재 확인
        boolean has = kc.userEffectiveClientRoles(me.id(), c.keycloakId).stream().anyMatch(r -> role.equals(r.get("name")));
        if (has) throw new PortalException("이미 가지고 있는 권한이에요: " + clientId + " / " + role);
        if (requests.existsByRequesterAndClientIdAndRoleNameAndStatus(me.username(), clientId, role, AccessRequest.Status.PENDING)) {
            throw new PortalException("이미 결재 대기 중인 신청이 있어요.");
        }
        AccessRequest r = requests.save(new AccessRequest(me.username(), me.id(), me.name(), clientId, role, reason));
        audit(me, "REQUEST_SUBMIT", clientId + "/" + role, "#" + r.id + " " + (reason == null ? "" : reason));
        return r;
    }

    /** PoC: 신청자 본인 셀프 승인 허용. 운영 시 소유자/팀장 등 결재선으로 교체 */
    public boolean canDecide(Me me, AccessRequest r) {
        if (r.status != AccessRequest.Status.PENDING) return false;
        if (me.username().equals(r.requester)) return true;
        return clients.findById(r.clientId).map(c -> canManage(me, c)).orElse(false);
    }

    public void approve(Me me, Long id, String comment) {
        AccessRequest r = requests.findById(id).orElseThrow(() -> new PortalException("신청서가 없어요: #" + id));
        if (!canDecide(me, r)) throw new PortalException("승인할 수 없는 신청이에요: #" + id);
        ManagedClient c = client(r.clientId);
        kc.assignUserClientRole(r.requesterId, c.keycloakId, role(c, r.roleName));
        decide(r, AccessRequest.Status.APPROVED, me, comment);
        audit(me, "REQUEST_APPROVE", r.clientId + "/" + r.roleName, "#" + r.id + " requester=" + r.requester + (me.username().equals(r.requester) ? " (셀프 승인)" : ""));
    }

    public void reject(Me me, Long id, String comment) {
        AccessRequest r = requests.findById(id).orElseThrow(() -> new PortalException("신청서가 없어요: #" + id));
        if (!canDecide(me, r)) throw new PortalException("반려할 수 없는 신청이에요: #" + id);
        decide(r, AccessRequest.Status.REJECTED, me, comment);
        audit(me, "REQUEST_REJECT", r.clientId + "/" + r.roleName, "#" + r.id + " requester=" + r.requester);
    }

    public void cancel(Me me, Long id) {
        AccessRequest r = requests.findById(id).orElseThrow(() -> new PortalException("신청서가 없어요: #" + id));
        if (!me.username().equals(r.requester) || r.status != AccessRequest.Status.PENDING) throw new PortalException("취소할 수 없는 신청이에요: #" + id);
        decide(r, AccessRequest.Status.CANCELED, me, null);
        audit(me, "REQUEST_CANCEL", r.clientId + "/" + r.roleName, "#" + r.id);
    }

    private void decide(AccessRequest r, AccessRequest.Status status, Me me, String comment) {
        r.status = status;
        r.decidedBy = me.username();
        r.decidedAt = Instant.now();
        r.decisionComment = comment;
        requests.save(r);
    }

    public List<AccessRequest> myRequests(Me me) { return requests.findByRequesterOrderByIdDesc(me.username()); }

    public List<AccessRequest> pendingRequests() { return requests.findByStatusOrderByIdDesc(AccessRequest.Status.PENDING); }

    public List<AccessRequest> recentRequests() { return requests.findTop50ByOrderByIdDesc(); }

    // ================================================================ 감사 로그

    public void audit(Me me, String action, String target, String detail) {
        audits.save(new AuditLog(me.username(), action, target, detail));
    }

    public List<AuditLog> audits() { return audits.findTop200ByOrderByIdDesc(); }

    private String username(String userId) {
        return kc.user(userId).map(u -> (String) u.get("username")).orElse(userId);
    }

    private String groupName(String groupId) {
        return kc.group(groupId).map(g -> (String) g.get("name")).orElse(groupId);
    }
}
