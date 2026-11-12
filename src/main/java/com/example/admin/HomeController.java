package com.example.admin;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;
import java.util.List;

@Controller
public class HomeController {

    @Value("${app.name}") private String appName;
    @Value("${app.color}") private String appColor;
    @Value("${app.other-name}") private String otherName;
    @Value("${app.other-url}") private String otherUrl;
    @Value("${app.required-roles}") private List<String> requiredRoles;

    @ModelAttribute
    void common(Model model) {
        model.addAttribute("appName", appName);
        model.addAttribute("appColor", appColor);
        model.addAttribute("otherName", otherName);
        model.addAttribute("otherUrl", otherUrl);
    }

    @GetMapping("/login")
    String login(Principal principal) {
        if (principal instanceof OAuth2AuthenticationToken) return "redirect:/";
        return "login";
    }

    @GetMapping("/")
    String home(@AuthenticationPrincipal OidcUser user, Model model) {
        fillUser(user, model);
        model.addAttribute("denied", false);
        return "home";
    }

    /** 필수 권한이 없을 때 (SecurityConfig의 accessDeniedPage에서 forward) */
    @GetMapping("/denied")
    String denied(@AuthenticationPrincipal OidcUser user, Model model, HttpServletResponse response) {
        List<String> held = fillUser(user, model);
        List<String> missing = requiredRoles.stream()
            .map(String::trim).filter(r -> !r.isBlank() && !held.contains(r)).toList();
        if (missing.isEmpty()) return "redirect:/";
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("denied", true);
        model.addAttribute("missingRoles", missing);
        return "home";
    }

    private List<String> fillUser(OidcUser user, Model model) {
        List<String> clientRoles = authoritiesWithPrefix(user, "ROLE_");
        model.addAttribute("name", user.getFullName() != null ? user.getFullName() : user.getPreferredUsername());
        model.addAttribute("username", user.getPreferredUsername());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("clientRoles", clientRoles);
        model.addAttribute("realmRoles", authoritiesWithPrefix(user, "REALM_"));
        return clientRoles;
    }

    private static List<String> authoritiesWithPrefix(OidcUser user, String prefix) {
        return user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(prefix))
            .map(a -> a.substring(prefix.length()))
            .sorted()
            .toList();
    }
}
