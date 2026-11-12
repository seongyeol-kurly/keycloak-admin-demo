package com.example.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
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
                                    OAuth2UserService<OidcUserRequest, OidcUser> keycloakOidcUserService,
                                    @Value("${app.required-roles}") List<String> requiredRoles) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error").permitAll()
                // 권한 요청 화면은 로그인만 되어 있으면 볼 수 있음
                .requestMatchers("/denied").authenticated()
                // 그 외 모든 페이지는 이 어드민의 필수 role을 모두 가지고 있어야 접근 가능
                .anyRequest().access(hasAllRoles(requiredRoles)))
            // 필수 role이 없으면 403 + 권한 요청 화면
            .exceptionHandling(e -> e.accessDeniedPage("/denied"))
            // 미로그인 시 Keycloak으로 바로 보내지 않고, 자체 로그인 페이지(원클릭 버튼)를 보여줌
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(keycloakOidcUserService)))
            // 로그아웃: Keycloak end_session(=SSO 전체 로그아웃)은 호출하지 않고,
            // 이 어드민의 refresh token만 revoke + 로컬 세션 삭제 → 이 어드민만 로그아웃
            .logout(logout -> logout
                .addLogoutHandler(localOnlyLogoutHandler)
                .logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    /** 로그인 + requiredRoles 전부 보유 (ROLE_{role}) 여부로 접근 판단 */
    static AuthorizationManager<RequestAuthorizationContext> hasAllRoles(List<String> requiredRoles) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                return new AuthorizationDecision(false);
            }
            Set<String> held = new HashSet<>();
            auth.getAuthorities().forEach(a -> held.add(a.getAuthority()));
            boolean ok = requiredRoles.stream().filter(r -> !r.isBlank()).allMatch(r -> held.contains("ROLE_" + r.trim()));
            return new AuthorizationDecision(ok);
        };
    }

    /**
     * Keycloak은 role을 ID token이 아니라 access token에 넣어줌.
     *  - realm role : realm_access.roles
     *  - client role: resource_access.{clientId}.roles
     * access token을 읽어서 권한(GrantedAuthority)으로 변환.
     *  - 이 어드민(client)의 role → ROLE_{role}  (hasRole() 로 페이지 권한 체크에 사용 가능)
     *  - realm role               → REALM_{role}
     */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> keycloakOidcUserService(ObjectMapper objectMapper) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            String clientId = request.getClientRegistration().getClientId();
            Map<String, Object> claims = decodeJwtPayload(request.getAccessToken().getTokenValue(), objectMapper);

            Set<GrantedAuthority> authorities = new LinkedHashSet<>();
            rolesAt(claims, "resource_access", clientId)
                .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            rolesAt(claims, "realm_access", null)
                .forEach(r -> authorities.add(new SimpleGrantedAuthority("REALM_" + r)));

            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "preferred_username");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesAt(Map<String, Object> claims, String key, String clientId) {
        Object node = claims.get(key);
        if (clientId != null && node instanceof Map<?, ?> m) node = m.get(clientId);
        if (node instanceof Map<?, ?> m && m.get("roles") instanceof List<?> roles) {
            return (List<String>) roles;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeJwtPayload(String jwt, ObjectMapper objectMapper) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
