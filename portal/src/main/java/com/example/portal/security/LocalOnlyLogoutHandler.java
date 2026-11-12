package com.example.portal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** 포털만 로그아웃 (refresh token revoke). 예시 어드민의 LocalOnlyLogoutHandler와 동일 */
@Component
public class LocalOnlyLogoutHandler implements LogoutHandler {

    private final OAuth2AuthorizedClientService clients;
    private final RestClient rest = RestClient.create();
    private final String revokeUri;

    public LocalOnlyLogoutHandler(OAuth2AuthorizedClientService clients,
                                  @Value("${app.keycloak.internal-url}") String internalUrl,
                                  @Value("${app.keycloak.realm}") String realm) {
        this.clients = clients;
        this.revokeUri = internalUrl + "/realms/" + realm + "/protocol/openid-connect/revoke";
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken t)) return;
        OAuth2AuthorizedClient c = clients.loadAuthorizedClient(t.getAuthorizedClientRegistrationId(), t.getName());
        if (c == null) return;
        if (c.getRefreshToken() != null) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", c.getRefreshToken().getTokenValue());
            form.add("token_type_hint", "refresh_token");
            try {
                rest.post().uri(revokeUri)
                    .headers(h -> h.setBasicAuth(c.getClientRegistration().getClientId(), c.getClientRegistration().getClientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            } catch (Exception ignored) {
            }
        }
        clients.removeAuthorizedClient(t.getAuthorizedClientRegistrationId(), t.getName());
    }
}
