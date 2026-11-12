package com.example.portal.web;

import com.example.portal.domain.ManagedClient;
import com.example.portal.keycloak.KeycloakAdminClient;
import com.example.portal.security.Me;
import com.example.portal.service.PortalService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;
import java.util.*;

@Controller
public class HomeController {

    private final PortalService portal;
    private final KeycloakAdminClient kc;

    public HomeController(PortalService portal, KeycloakAdminClient kc) {
        this.portal = portal;
        this.kc = kc;
    }

    @GetMapping("/login")
    String login(Principal principal) {
        return principal instanceof OAuth2AuthenticationToken ? "redirect:/" : "login";
    }

    /** 대시보드: 내 권한 현황 + 내 신청 */
    @GetMapping("/")
    String home(@ModelAttribute(name = "me", binding = false) Me me, Model model) {
        List<Map<String, Object>> myAccess = new ArrayList<>();
        for (ManagedClient c : portal.clients()) {
            List<String> roles = kc.userEffectiveClientRoles(me.id(), c.keycloakId).stream()
                .map(r -> (String) r.get("name")).sorted().toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client", c);
            row.put("roles", roles);
            row.put("canManage", portal.canManage(me, c));
            myAccess.add(row);
        }
        model.addAttribute("myAccess", myAccess);
        model.addAttribute("myRequests", portal.myRequests(me).stream().limit(5).toList());
        model.addAttribute("pendingCount", portal.pendingRequests().size());
        model.addAttribute("myGroups", kc.userGroups(me.id()));
        return "home";
    }

    @GetMapping("/audit")
    String audit(Model model) {
        model.addAttribute("logs", portal.audits());
        return "audit";
    }
}
