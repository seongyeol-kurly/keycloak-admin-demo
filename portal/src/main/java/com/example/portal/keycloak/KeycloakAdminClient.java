package com.example.portal.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Keycloak Admin REST API 클라이언트.
 * 포털 서비스 계정(client_credentials) 토큰으로 호출한다. 브라우저에서 직접 호출하지 않는다.
 * 서비스 계정 권한(realm-management): manage-clients, manage-users, view-*, query-* (최소 권한)
 */
@Component
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestClient rest = RestClient.create();
    private final String adminBase;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public KeycloakAdminClient(@Value("${app.keycloak.internal-url}") String internalUrl,
                               @Value("${app.keycloak.realm}") String realm,
                               @Value("${app.keycloak.admin-client-id}") String clientId,
                               @Value("${app.keycloak.admin-client-secret}") String clientSecret) {
        this.adminBase = internalUrl + "/admin/realms/" + realm;
        this.tokenUri = internalUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ---------------------------------------------------------------- token

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) return token;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> res = rest.post().uri(tokenUri)
            .headers(h -> h.setBasicAuth(clientId, clientSecret))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(MAP);
        token = (String) res.get("access_token");
        tokenExpiresAt = Instant.now().plusSeconds(((Number) res.get("expires_in")).longValue());
        return token;
    }

    private List<Map<String, Object>> getList(String path, Object... vars) {
        List<Map<String, Object>> r = rest.get().uri(adminBase + path, vars)
            .headers(h -> h.setBearerAuth(token())).retrieve().body(LIST);
        return r == null ? List.of() : r;
    }

    private Optional<Map<String, Object>> getOne(String path, Object... vars) {
        try {
            return Optional.ofNullable(rest.get().uri(adminBase + path, vars)
                .headers(h -> h.setBearerAuth(token())).retrieve().body(MAP));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** POST 후 생성된 리소스 id(Location 마지막 segment) 반환 */
    private String post(String path, Object body, Object... vars) {
        URI location = rest.post().uri(adminBase + path, vars)
            .headers(h -> h.setBearerAuth(token()))
            .contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().toBodilessEntity().getHeaders().getLocation();
        if (location == null) return null;
        String p = location.getPath();
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private void send(HttpMethod method, String path, Object body, Object... vars) {
        var spec = rest.method(method).uri(adminBase + path, vars).headers(h -> h.setBearerAuth(token()));
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        spec.retrieve().toBodilessEntity();
    }

    // ---------------------------------------------------------------- clients

    public Optional<Map<String, Object>> clientByClientId(String clientId) {
        return getList("/clients?clientId={c}", clientId).stream().findFirst();
    }

    public String createClient(String clientId, String name, String baseUrl, String owner) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("name", name);
        body.put("publicClient", false);
        body.put("standardFlowEnabled", true);
        body.put("directAccessGrantsEnabled", false);
        body.put("frontchannelLogout", false);
        body.put("rootUrl", baseUrl);
        body.put("redirectUris", List.of(baseUrl + "/*"));
        body.put("webOrigins", List.of(baseUrl));
        body.put("attributes", Map.of("portal.owner", owner));
        return post("/clients", body);
    }

    public String clientSecret(String id) {
        return (String) getOne("/clients/{id}/client-secret", id).map(m -> m.get("value")).orElse(null);
    }

    public String regenerateSecret(String id) {
        Map<String, Object> r = rest.post().uri(adminBase + "/clients/{id}/client-secret", id)
            .headers(h -> h.setBearerAuth(token())).retrieve().body(MAP);
        return r == null ? null : (String) r.get("value");
    }

    public void deleteClient(String id) { send(HttpMethod.DELETE, "/clients/{id}", null, id); }

    // ---------------------------------------------------------------- client roles

    public List<Map<String, Object>> clientRoles(String id) { return getList("/clients/{id}/roles", id); }

    public Map<String, Object> clientRole(String id, String role) {
        return getOne("/clients/{id}/roles/{r}", id, role).orElseThrow(() -> new IllegalArgumentException("role 없음: " + role));
    }

    public void createClientRole(String id, String role, String description) {
        post("/clients/{id}/roles", Map.of("name", role, "description", description == null ? "" : description), id);
    }

    public void deleteClientRole(String id, String role) { send(HttpMethod.DELETE, "/clients/{id}/roles/{r}", null, id, role); }

    public List<Map<String, Object>> roleUsers(String id, String role) { return getList("/clients/{id}/roles/{r}/users?max=200", id, role); }

    public List<Map<String, Object>> roleGroups(String id, String role) { return getList("/clients/{id}/roles/{r}/groups", id, role); }

    // ---------------------------------------------------------------- users

    public List<Map<String, Object>> searchUsers(String q) {
        return getList("/users?search={q}&max=50&briefRepresentation=true", q == null ? "" : q);
    }

    public Optional<Map<String, Object>> user(String userId) { return getOne("/users/{id}", userId); }

    public Optional<Map<String, Object>> userByUsername(String username) {
        return getList("/users?username={u}&exact=true", username).stream().findFirst();
    }

    public List<Map<String, Object>> userGroups(String userId) { return getList("/users/{id}/groups", userId); }

    /** 직접 부여된 client role */
    public List<Map<String, Object>> userClientRoles(String userId, String clientUuid) {
        return getList("/users/{u}/role-mappings/clients/{c}", userId, clientUuid);
    }

    /** 그룹 상속 포함 실효 client role */
    public List<Map<String, Object>> userEffectiveClientRoles(String userId, String clientUuid) {
        return getList("/users/{u}/role-mappings/clients/{c}/composite", userId, clientUuid);
    }

    public void assignUserClientRole(String userId, String clientUuid, Map<String, Object> role) {
        send(HttpMethod.POST, "/users/{u}/role-mappings/clients/{c}", List.of(role), userId, clientUuid);
    }

    public void unassignUserClientRole(String userId, String clientUuid, Map<String, Object> role) {
        send(HttpMethod.DELETE, "/users/{u}/role-mappings/clients/{c}", List.of(role), userId, clientUuid);
    }

    public void logoutUser(String userId) { send(HttpMethod.POST, "/users/{id}/logout", null, userId); }

    // ---------------------------------------------------------------- groups

    public List<Map<String, Object>> groups() { return getList("/groups?briefRepresentation=true&max=200"); }

    public Optional<Map<String, Object>> group(String id) { return getOne("/groups/{id}", id); }

    public String createGroup(String name) { return post("/groups", Map.of("name", name)); }

    public List<Map<String, Object>> groupMembers(String id) { return getList("/groups/{id}/members?max=200", id); }

    public List<Map<String, Object>> groupClientRoles(String groupId, String clientUuid) {
        return getList("/groups/{g}/role-mappings/clients/{c}", groupId, clientUuid);
    }

    public void assignGroupClientRole(String groupId, String clientUuid, Map<String, Object> role) {
        send(HttpMethod.POST, "/groups/{g}/role-mappings/clients/{c}", List.of(role), groupId, clientUuid);
    }

    public void unassignGroupClientRole(String groupId, String clientUuid, Map<String, Object> role) {
        send(HttpMethod.DELETE, "/groups/{g}/role-mappings/clients/{c}", List.of(role), groupId, clientUuid);
    }

    public void joinGroup(String userId, String groupId) { send(HttpMethod.PUT, "/users/{u}/groups/{g}", null, userId, groupId); }

    public void leaveGroup(String userId, String groupId) { send(HttpMethod.DELETE, "/users/{u}/groups/{g}", null, userId, groupId); }
}
