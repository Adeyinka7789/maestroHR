package com.admtechhub.maestrohr.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {
    private UUID tenantId;
    private String actorEmail;
    private String action;
    private String entityType;
    private String entityId;
    private String requestPath;
    private String httpMethod;
    private String ipAddress;
    private int statusCode;
    private String details;
    private String impersonatedBy;
}
