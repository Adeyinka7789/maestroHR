package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default {@link FlagAuditListener}: writes each flag change to MaestroHR's audit trail as a
 * {@code FEATURE_FLAG_CHANGED} / {@code PLATFORM_FLAG} entry (platform-scoped, so {@code tenantId}
 * is null). Isolating the fixed audit-row shape here keeps it out of the engine.
 */
@Component
@RequiredArgsConstructor
public class AuditTrailFlagListener implements FlagAuditListener {

    private final AuditTrailService auditTrailService;

    @Override
    public void onFlagChanged(FlagChange change) {
        auditTrailService.record(null, change.actor(), "FEATURE_FLAG_CHANGED",
                "PLATFORM_FLAG", change.flagName(), "/admin/feature-flags", "POST",
                null, 200, change.detail());
    }
}
