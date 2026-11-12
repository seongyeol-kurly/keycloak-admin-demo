package com.example.portal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    LocalOnlyLogoutHandler localOnlyLogoutHandler,
                                    OAuth2UserService<OidcUserRequest, OidcUser> keycloakOidcUserService) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**").permitAll()
                // 포털은 임직원 누구나 로그인해서 사용 (세부 권한은 PortalService에서 소유자/portal-admin 기준으로 체크)
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .userInfoEndpoint(u -> u.oidcUserService(keycloakOidcUserService)))
            .logout(logout -> logout
                .addLogoutHandler(localOnlyLogoutHandler)
                .logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    /** access token의 resource_access.{clientId}.roles → ROLE_{role} (예: ROLE_portal-admin) */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> keycloakOidcUserService(ObjectMapper objectMapper) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            String clientId = request.getClientRegistration().getClientId();
            Map<String, Object> claims = decode(request.getAccessToken().getTokenValue(), objectMapper);
            Set<GrantedAuthority> authorities = new LinkedHashSet<>();
            rolesAt(claims, clientId).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "preferred_username");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesAt(Map<String, Object> claims, String clientId) {
        if (claims.get("resource_access") instanceof Map<?, ?> ra
            && ra.get(clientId) instanceof Map<?, ?> c
            && c.get("roles") instanceof List<?> roles) {
            return (List<String>) roles;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String jwt, ObjectMapper om) {
        try {
            return om.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
