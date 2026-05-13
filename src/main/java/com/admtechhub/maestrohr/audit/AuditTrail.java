package com.admtechhub.maestrohr.audit;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "audit_trail")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AuditTrail extends BaseEntity {

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "entity_type", length = 120)
    private String entityType;

    @Column(name = "entity_id", length = 120)
    private String entityId;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "http_method", nullable = false, length = 16)
    private String httpMethod;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "status_code", nullable = false)
    private Integer statusCode;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
}
