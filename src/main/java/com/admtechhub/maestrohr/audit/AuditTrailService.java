package com.admtechhub.maestrohr.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditTrailService {

    private final AuditTrailRepository auditTrailRepository;

    @Transactional
    public void record(UUID tenantId, String actorEmail, String action, String entityType,
                       String entityId, String requestPath, String httpMethod,
                       String ipAddress, int statusCode, String details) {
        try {
            auditTrailRepository.save(AuditTrail.builder()
                    .tenantId(tenantId)
                    .actorEmail(actorEmail)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .requestPath(requestPath)
                    .httpMethod(httpMethod)
                    .ipAddress(ipAddress)
                    .statusCode(statusCode)
                    .details(details)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to persist audit trail for {} {}: {}", httpMethod, requestPath, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AuditTrail> findBetween(OffsetDateTime from, OffsetDateTime to) {
        return auditTrailRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
    }
}
