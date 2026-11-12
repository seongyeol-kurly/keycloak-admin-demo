package com.example.portal.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** 임직원 권한 신청서 (결재) */
@Entity
public class AccessRequest {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELED }

    @Id @GeneratedValue
    public Long id;
    public String requester;      // username
    public String requesterId;    // keycloak user id
    public String requesterName;
    public String clientId;
    public String roleName;
    @Column(length = 1000)
    public String reason;
    @Enumerated(EnumType.STRING)
    public Status status;
    public Instant createdAt;
    public String decidedBy;
    public Instant decidedAt;
    @Column(length = 1000)
    public String decisionComment;

    protected AccessRequest() {}

    public AccessRequest(String requester, String requesterId, String requesterName, String clientId, String roleName, String reason) {
        this.requester = requester;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.clientId = clientId;
        this.roleName = roleName;
        this.reason = reason;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }
}
