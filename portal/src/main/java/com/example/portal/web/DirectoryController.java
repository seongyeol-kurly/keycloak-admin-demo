package com.example.portal.web;

import com.example.portal.domain.ManagedClient;
import com.example.portal.keycloak.KeycloakAdminClient;
import com.example.portal.security.Me;
import com.example.portal.service.PortalException;
import com.example.portal.service.PortalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

import static com.example.portal.web.WebSupport.run;

/** 임직원 / 그룹 권한 관리 */
@Controller
public class DirectoryController {

    private final PortalService portal;
    private final KeycloakAdminClient kc;

    public DirectoryController(PortalService portal, KeycloakAdminClient kc) {
        this.portal = portal;
        this.kc = kc;
    }

    // ================================================================ 임직원

    @GetMapping("/users")
    String users(@RequestParam(required = false, defaultValue = "") String q, Model model) {
        model.addAttribute("q", q);
        model.addAttribute("users", kc.searchUsers(q));
        return "users";
    }

    @GetMapping("/users/{id}")
    String user(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id, Model model) {
        Map<String, Object> u = kc.user(id).orElseThrow(() -> new PortalException("사용자가 없어요"));
        List<Map<String, Object>> access = new ArrayList<>();
        for (ManagedClient c : portal.clients()) {
            Set<String> direct = names(kc.userClientRoles(id, c.keycloakId));
            Set<String> effective = names(kc.userEffectiveClientRoles(id, c.keycloakId));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client", c);
            row.put("allRoles", portal.roleNames(c));
            row.put("direct", direct);
            row.put("effective", effective);
            row.put("canManage", portal.canManage(me, c));
            access.add(row);
        }
        model.addAttribute("u", u);
        model.addAttribute("groups", kc.userGroups(id));
        model.addAttribute("allGroups", kc.groups());
        model.addAttribute("access", access);
        return "user-detail";
    }

    @PostMapping("/users/{id}/roles")
    String assignRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                      @RequestParam String clientId, @RequestParam String role, RedirectAttributes ra) {
        return run(ra, "/users/" + id, "'" + clientId + " / " + role + "'을 부여했어요.",
            () -> portal.assignRoleToUser(me, clientId, role, usernameOf(id)));
    }

    @PostMapping("/users/{id}/roles/delete")
    String unassignRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                        @RequestParam String clientId, @RequestParam String role, RedirectAttributes ra) {
        return run(ra, "/users/" + id, "'" + clientId + " / " + role + "'을 회수했어요.",
            () -> portal.unassignRoleFromUser(me, clientId, role, id));
    }

    @PostMapping("/users/{id}/groups")
    String joinGroup(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                     @RequestParam String groupId, RedirectAttributes ra) {
        return run(ra, "/users/" + id, "그룹에 추가했어요.", () -> portal.joinGroup(me, groupId, usernameOf(id)));
    }

    @PostMapping("/users/{id}/groups/{groupId}/delete")
    String leaveGroup(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                      @PathVariable String groupId, RedirectAttributes ra) {
        return run(ra, "/users/" + id, "그룹에서 제외했어요.", () -> portal.leaveGroup(me, groupId, id));
    }

    // ================================================================ 그룹

    @GetMapping("/groups")
    String groups(Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> g : kc.groups()) {
            Map<String, Object> row = new LinkedHashMap<>(g);
            row.put("memberCount", kc.groupMembers((String) g.get("id")).size());
            rows.add(row);
        }
        model.addAttribute("groups", rows);
        return "groups";
    }

    @PostMapping("/groups")
    String createGroup(@ModelAttribute(name = "me", binding = false) Me me, @RequestParam String name, RedirectAttributes ra) {
        return run(ra, "/groups", "그룹 '" + name + "'을 만들었어요.", () -> portal.createGroup(me, name));
    }

    @GetMapping("/groups/{id}")
    String group(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id, Model model) {
        Map<String, Object> g = kc.group(id).orElseThrow(() -> new PortalException("그룹이 없어요"));
        List<Map<String, Object>> access = new ArrayList<>();
        for (ManagedClient c : portal.clients()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client", c);
            row.put("allRoles", portal.roleNames(c));
            row.put("direct", names(kc.groupClientRoles(id, c.keycloakId)));
            row.put("canManage", portal.canManage(me, c));
            access.add(row);
        }
        model.addAttribute("g", g);
        model.addAttribute("members", kc.groupMembers(id));
        model.addAttribute("access", access);
        return "group-detail";
    }

    @PostMapping("/groups/{id}/members")
    String addMember(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                     @RequestParam String username, RedirectAttributes ra) {
        return run(ra, "/groups/" + id, username + "을(를) 그룹에 추가했어요.", () -> portal.joinGroup(me, id, username));
    }

    @PostMapping("/groups/{id}/members/{userId}/delete")
    String removeMember(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                        @PathVariable String userId, RedirectAttributes ra) {
        return run(ra, "/groups/" + id, "그룹에서 제외했어요.", () -> portal.leaveGroup(me, id, userId));
    }

    @PostMapping("/groups/{id}/roles")
    String assignGroupRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                           @RequestParam String clientId, @RequestParam String role, RedirectAttributes ra) {
        return run(ra, "/groups/" + id, "그룹에 '" + clientId + " / " + role + "'을 부여했어요.",
            () -> portal.assignRoleToGroup(me, clientId, role, id));
    }

    @PostMapping("/groups/{id}/roles/delete")
    String unassignGroupRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String id,
                             @RequestParam String clientId, @RequestParam String role, RedirectAttributes ra) {
        return run(ra, "/groups/" + id, "그룹의 '" + clientId + " / " + role + "'을 회수했어요.",
            () -> portal.unassignRoleFromGroup(me, clientId, role, id));
    }

    // ----------------------------------------------------------------

    private String usernameOf(String userId) {
        return kc.user(userId).map(u -> (String) u.get("username")).orElseThrow(() -> new PortalException("사용자가 없어요"));
    }

    private static Set<String> names(List<Map<String, Object>> roles) {
        Set<String> s = new TreeSet<>();
        roles.forEach(r -> s.add((String) r.get("name")));
        return s;
    }
}
