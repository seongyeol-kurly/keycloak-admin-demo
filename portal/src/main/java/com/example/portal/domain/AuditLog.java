package com.example.portal.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 감사 로그. Keycloak admin event에는 실행자가 "포털 서비스 계정"으로만 남으므로,
 * 실제로 누가 했는지는 포털이 직접 기록한다.
 */
@Entity
public class AuditLog {
    @Id @GeneratedValue
    public Long id;
    public String actor;
    public String action;
    public String target;
    @Column(length = 1000)
    public String detail;
    public Instant at;

    protected AuditLog() {}

    public AuditLog(String actor, String action, String target, String detail) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.at = Instant.now();
    }
}
