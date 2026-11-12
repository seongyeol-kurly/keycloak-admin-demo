package com.example.portal.web;

import com.example.portal.domain.ManagedClient;
import com.example.portal.keycloak.KeycloakAdminClient;
import com.example.portal.security.Me;
import com.example.portal.service.PortalException;
import com.example.portal.service.PortalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

import static com.example.portal.web.WebSupport.run;

/** 어드민(client) 등록 + 어드민별 권한(client role) 관리 */
@Controller
@RequestMapping("/clients")
public class ClientController {

    private final PortalService portal;
    private final KeycloakAdminClient kc;

    public ClientController(PortalService portal, KeycloakAdminClient kc) {
        this.portal = portal;
        this.kc = kc;
    }

    @GetMapping
    String list(@ModelAttribute(name = "me", binding = false) Me me, Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManagedClient c : portal.clients()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client", c);
            row.put("canManage", portal.canManage(me, c));
            row.put("roleCount", portal.roleNames(c).size());
            rows.add(row);
        }
        model.addAttribute("rows", rows);
        return "clients";
    }

    /** 신규 어드민 등록 → secret은 이 화면에서 한 번만 보여줌 */
    @PostMapping
    String register(@ModelAttribute(name = "me", binding = false) Me me, @RequestParam String clientId, @RequestParam String name,
                    @RequestParam String baseUrl, Model model, RedirectAttributes ra) {
        try {
            PortalService.Registered r = portal.registerClient(me, clientId, name, baseUrl);
            model.addAttribute("client", r.client());
            model.addAttribute("secret", r.secret());
            model.addAttribute("title", "어드민이 등록됐어요");
            return "client-secret";
        } catch (PortalException e) {
            ra.addFlashAttribute("err", e.getMessage());
        } catch (HttpStatusCodeException e) {
            ra.addFlashAttribute("err", "Keycloak 오류 (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        }
        return "redirect:/clients";
    }

    @GetMapping("/{clientId}")
    String detail(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, Model model) {
        ManagedClient c = portal.client(clientId);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map<String, Object> r : kc.clientRoles(c.keycloakId)) {
            String name = (String) r.get("name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("description", r.getOrDefault("description", ""));
            row.put("users", kc.roleUsers(c.keycloakId, name));
            row.put("groups", kc.roleGroups(c.keycloakId, name));
            roles.add(row);
        }
        roles.sort(Comparator.comparing(m -> (String) m.get("name")));
        model.addAttribute("client", c);
        model.addAttribute("roles", roles);
        model.addAttribute("canManage", portal.canManage(me, c));
        model.addAttribute("allGroups", kc.groups());
        return "client-detail";
    }

    @PostMapping("/{clientId}/secret")
    String regenerateSecret(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, Model model, RedirectAttributes ra) {
        try {
            String secret = portal.regenerateSecret(me, clientId);
            model.addAttribute("client", portal.client(clientId));
            model.addAttribute("secret", secret);
            model.addAttribute("title", "client secret을 재발급했어요");
            return "client-secret";
        } catch (PortalException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/clients/" + clientId;
        }
    }

    @PostMapping("/{clientId}/owner")
    String owner(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @RequestParam String owner, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "소유자를 " + owner + "(으)로 변경했어요.", () -> portal.changeOwner(me, clientId, owner));
    }

    @PostMapping("/{clientId}/roles")
    String createRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @RequestParam String role,
                      @RequestParam(required = false) String description, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "권한 '" + role + "'을 만들었어요.", () -> portal.createRole(me, clientId, role, description));
    }

    @PostMapping("/{clientId}/roles/{role}/delete")
    String deleteRole(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @PathVariable String role, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "권한 '" + role + "'을 삭제했어요.", () -> portal.deleteRole(me, clientId, role));
    }

    @PostMapping("/{clientId}/roles/{role}/users")
    String assignUser(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @PathVariable String role,
                      @RequestParam String username, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, username + "에게 '" + role + "'을 부여했어요.", () -> portal.assignRoleToUser(me, clientId, role, username));
    }

    @PostMapping("/{clientId}/roles/{role}/users/{userId}/delete")
    String unassignUser(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @PathVariable String role,
                        @PathVariable String userId, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "'" + role + "' 부여를 회수했어요.", () -> portal.unassignRoleFromUser(me, clientId, role, userId));
    }

    @PostMapping("/{clientId}/roles/{role}/groups")
    String assignGroup(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @PathVariable String role,
                       @RequestParam String groupId, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "그룹에 '" + role + "'을 부여했어요.", () -> portal.assignRoleToGroup(me, clientId, role, groupId));
    }

    @PostMapping("/{clientId}/roles/{role}/groups/{groupId}/delete")
    String unassignGroup(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable String clientId, @PathVariable String role,
                         @PathVariable String groupId, RedirectAttributes ra) {
        return run(ra, "/clients/" + clientId, "그룹의 '" + role + "'을 회수했어요.", () -> portal.unassignRoleFromGroup(me, clientId, role, groupId));
    }
}
