package com.example.portal.web;

import com.example.portal.domain.AccessRequest;
import com.example.portal.domain.ManagedClient;
import com.example.portal.security.Me;
import com.example.portal.service.PortalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

import static com.example.portal.web.WebSupport.run;

/** 권한 신청 → 결재(승인/반려) → 승인 시 Keycloak에 role 부여 */
@Controller
@RequestMapping("/requests")
public class RequestController {

    private final PortalService portal;

    public RequestController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping
    String page(@ModelAttribute(name = "me", binding = false) Me me, Model model) {
        // 신청 가능한 권한 = 포털에 등록된 모든 어드민의 모든 client role (PoC)
        List<Map<String, String>> options = new ArrayList<>();
        for (ManagedClient c : portal.clients()) {
            for (String role : portal.roleNames(c)) {
                options.add(Map.of("value", c.clientId + "|" + role, "label", (c.name == null ? c.clientId : c.name) + " (" + c.clientId + ") / " + role));
            }
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (AccessRequest r : portal.pendingRequests()) {
            pending.add(Map.of("r", r, "canDecide", portal.canDecide(me, r), "self", r.requester.equals(me.username())));
        }
        model.addAttribute("options", options);
        model.addAttribute("pending", pending);
        model.addAttribute("mine", portal.myRequests(me));
        model.addAttribute("recent", portal.recentRequests());
        return "requests";
    }

    @PostMapping
    String submit(@ModelAttribute(name = "me", binding = false) Me me, @RequestParam String target,
                  @RequestParam(required = false) String reason, RedirectAttributes ra) {
        String[] p = target.split("\\|", 2);
        return run(ra, "/requests", "권한을 신청했어요. 결재함에서 승인하면 부여돼요.",
            () -> portal.submitRequest(me, p[0], p.length > 1 ? p[1] : "", reason));
    }

    @PostMapping("/{id}/approve")
    String approve(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable Long id,
                   @RequestParam(required = false) String comment, RedirectAttributes ra) {
        return run(ra, "/requests", "#" + id + " 승인 완료 → Keycloak에 권한이 부여됐어요. (해당 어드민에서 재로그인하면 반영)",
            () -> portal.approve(me, id, comment));
    }

    @PostMapping("/{id}/reject")
    String reject(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable Long id,
                  @RequestParam(required = false) String comment, RedirectAttributes ra) {
        return run(ra, "/requests", "#" + id + " 반려했어요.", () -> portal.reject(me, id, comment));
    }

    @PostMapping("/{id}/cancel")
    String cancel(@ModelAttribute(name = "me", binding = false) Me me, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, "/requests", "#" + id + " 신청을 취소했어요.", () -> portal.cancel(me, id));
    }
}
