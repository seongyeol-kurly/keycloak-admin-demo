package com.example.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * "이 어드민만" 로그아웃.
 * Keycloak의 revoke 엔드포인트에 이 어드민이 가진 refresh token을 폐기 요청 →
 * Keycloak에서 이 어드민(client)의 client 세션만 정리되고, SSO 세션과 다른 어드민 세션은 유지됨.
 * 로컬 세션(쿠키)은 이후 Spring Security 기본 핸들러가 삭제.
 */
@Component
public class LocalOnlyLogoutHandler implements LogoutHandler {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyLogoutHandler.class);

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final RestClient restClient = RestClient.create();
    private final String revokeUri;

    public LocalOnlyLogoutHandler(OAuth2AuthorizedClientService authorizedClientService,
                                  @Value("${app.keycloak.internal-url}") String internalUrl,
                                  @Value("${app.keycloak.realm}") String realm) {
        this.authorizedClientService = authorizedClientService;
        this.revokeUri = internalUrl + "/realms/" + realm + "/protocol/openid-connect/revoke";
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) return;

        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(registrationId, token.getName());
        if (client == null) return;

        if (client.getRefreshToken() != null) {
            ClientRegistration reg = client.getClientRegistration();
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", client.getRefreshToken().getTokenValue());
            form.add("token_type_hint", "refresh_token");
            try {
                restClient.post()
                    .uri(revokeUri)
                    .headers(h -> h.setBasicAuth(reg.getClientId(), reg.getClientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
                log.info("[logout] revoked refresh token for user={} client={}", token.getName(), reg.getClientId());
            } catch (Exception e) {
                log.warn("[logout] revoke failed: {}", e.getMessage());
            }
        }
        authorizedClientService.removeAuthorizedClient(registrationId, token.getName());
    }
}
