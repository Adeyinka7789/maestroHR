package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant's overtime rate card (see V64): the standard working day, the divisor that turns a
 * monthly gross into an hourly rate, and the weekday / weekend / holiday multipliers. Exactly one
 * active (non-deleted) policy exists per tenant; {@link OvertimeService} seeds a default on first
 * use. Tenant-scoped + soft-deleted, mirroring {@code AttendancePolicy}.
 */
@Entity
@Table(name = "overtime_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class OvertimePolicy extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 100)
    @Builder.Default
    private String name = "Default Overtime Policy";

    /** Hours in a normal working day; weekday hours beyond this are overtime. */
    @Column(name = "standard_daily_hours", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal standardDailyHours = new BigDecimal("8.00");

    /** Divisor turning a monthly gross (kobo) into an hourly rate: gross / standardMonthlyHours. */
    @Column(name = "standard_monthly_hours", nullable = false)
    @Builder.Default
    private Integer standardMonthlyHours = 173;

    @Column(name = "weekday_multiplier", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal weekdayMultiplier = new BigDecimal("1.50");

    @Column(name = "weekend_multiplier", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal weekendMultiplier = new BigDecimal("2.00");

    /** Reserved: applied once a public-holiday calendar exists (future work). */
    @Column(name = "holiday_multiplier", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal holidayMultiplier = new BigDecimal("2.00");

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
