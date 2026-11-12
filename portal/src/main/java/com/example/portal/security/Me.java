package com.example.portal.security;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** 현재 로그인한 포털 사용자 */
public record Me(String id, String username, String name, String email, boolean portalAdmin) {

    public static final String PORTAL_ADMIN = "portal-admin";

    public static Me of(OidcUser u) {
        boolean admin = u.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + PORTAL_ADMIN));
        String name = u.getFullName() != null ? u.getFullName() : u.getPreferredUsername();
        return new Me(u.getSubject(), u.getPreferredUsername(), name, u.getEmail(), admin);
    }

    // Thymeleaf(SpEL)에서 ${me.username} 형태로 읽기 위한 getter
    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public boolean isPortalAdmin() { return portalAdmin; }
}
