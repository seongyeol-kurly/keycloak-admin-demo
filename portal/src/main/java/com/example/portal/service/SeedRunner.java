package com.example.portal.service;

import com.example.portal.domain.ManagedClient;
import com.example.portal.domain.Repositories.ManagedClientRepository;
import com.example.portal.keycloak.KeycloakAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 기존에 만든 예시 어드민(order-admin, settle-admin)을 포털 관리 대상으로 가져온다 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final KeycloakAdminClient kc;
    private final ManagedClientRepository clients;
    private final List<String> seeds;

    public SeedRunner(KeycloakAdminClient kc, ManagedClientRepository clients, @Value("${app.seed-clients}") List<String> seeds) {
        this.kc = kc;
        this.clients = clients;
        this.seeds = seeds;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String seed : seeds) {
            if (seed.isBlank()) continue;
            String[] p = seed.split(":", 2);
            String clientId = p[0].trim();
            String owner = p.length > 1 && !p[1].isBlank() ? p[1].trim() : null;
            if (clients.existsById(clientId)) continue;
            try {
                kc.clientByClientId(clientId).ifPresent(c -> {
                    String base = (String) c.getOrDefault("rootUrl", "");
                    clients.save(new ManagedClient(clientId, (String) c.get("id"), (String) c.getOrDefault("name", clientId), base, owner));
                    log.info("[seed] imported client {} owner={}", clientId, owner);
                });
            } catch (Exception e) {
                log.warn("[seed] failed {}: {}", clientId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unused")
    private static String str(Map<String, Object> m, String k) { return m.get(k) == null ? "" : m.get(k).toString(); }
}
