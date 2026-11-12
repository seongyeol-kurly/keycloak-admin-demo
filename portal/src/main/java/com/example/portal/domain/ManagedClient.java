package com.example.portal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/** 포털에서 관리하는 어드민(client)과 소유자. 소유자 = 해당 어드민의 권한을 관리할 수 있는 사람 */
@Entity
public class ManagedClient {
    @Id
    public String clientId;
    public String keycloakId;
    public String name;
    public String baseUrl;
    /** 소유자 username. 비어 있으면 portal-admin만 관리 가능 */
    public String owner;
    public Instant createdAt;

    protected ManagedClient() {}

    public ManagedClient(String clientId, String keycloakId, String name, String baseUrl, String owner) {
        this.clientId = clientId;
        this.keycloakId = keycloakId;
        this.name = name;
        this.baseUrl = baseUrl;
        this.owner = owner;
        this.createdAt = Instant.now();
    }
}
