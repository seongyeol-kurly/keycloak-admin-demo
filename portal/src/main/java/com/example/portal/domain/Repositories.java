package com.example.portal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface Repositories {

    interface ManagedClientRepository extends JpaRepository<ManagedClient, String> {
        List<ManagedClient> findAllByOrderByClientIdAsc();
    }

    interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {
        List<AccessRequest> findByRequesterOrderByIdDesc(String requester);
        List<AccessRequest> findByStatusOrderByIdDesc(AccessRequest.Status status);
        List<AccessRequest> findTop50ByOrderByIdDesc();
        boolean existsByRequesterAndClientIdAndRoleNameAndStatus(String requester, String clientId, String roleName, AccessRequest.Status status);
    }

    interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
        List<AuditLog> findTop200ByOrderByIdDesc();
    }
}
