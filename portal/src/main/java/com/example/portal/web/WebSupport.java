package com.example.portal.web;

import com.example.portal.security.Me;
import com.example.portal.service.PortalException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 공통: 모든 화면에 현재 사용자(me) 주입 + POST 처리 결과를 flash 메시지로 */
@ControllerAdvice
public class WebSupport {

    @ModelAttribute("me")
    Me me(@AuthenticationPrincipal OidcUser user) {
        return user == null ? null : Me.of(user);
    }

    /** 액션 실행 → 성공/실패 메시지를 flash로 담고 redirect */
    public static String run(RedirectAttributes ra, String redirect, String success, Runnable action) {
        try {
            action.run();
            ra.addFlashAttribute("ok", success);
        } catch (PortalException | IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        } catch (HttpStatusCodeException e) {
            ra.addFlashAttribute("err", "Keycloak 오류 (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        }
        return "redirect:" + redirect;
    }
}
