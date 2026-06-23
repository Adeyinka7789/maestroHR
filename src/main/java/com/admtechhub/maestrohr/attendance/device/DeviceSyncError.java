package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "device_sync_errors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class DeviceSyncError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_api_key_id")
    private DeviceApiKey deviceApiKey;

    @Column(name = "device_employee_id", length = 100)
    private String deviceEmployeeId;

    @Column(name = "device_identifier", length = 100)
    private String deviceIdentifier;

    @Column(name = "event_type", length = 20)
    private String eventType;

    @Column(name = "event_timestamp")
    private OffsetDateTime eventTimestamp;

    @Column(name = "error_reason", nullable = false, length = 500)
    private String errorReason;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
