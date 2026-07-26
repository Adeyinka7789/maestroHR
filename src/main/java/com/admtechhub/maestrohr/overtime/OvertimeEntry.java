package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One employee's computed overtime for a pay period (see V64). Produced by
 * {@link OvertimeService#computeForPeriod} from attendance, reviewed by HR, and — on approval —
 * linked to the {@code payroll_adjustment} it emits (of the seeded OVERTIME type), so the payroll
 * run consumes it through the standard V61 adjustment path. At most one entry per employee/period
 * (unique constraint); recompute upserts.
 */
@Entity
@Table(name = "overtime_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class OvertimeEntry extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "weekday_ot_hours", nullable = false, precision = 7, scale = 2)
    @Builder.Default
    private BigDecimal weekdayOtHours = BigDecimal.ZERO;

    @Column(name = "weekend_ot_hours", nullable = false, precision = 7, scale = 2)
    @Builder.Default
    private BigDecimal weekendOtHours = BigDecimal.ZERO;

    @Column(name = "holiday_ot_hours", nullable = false, precision = 7, scale = 2)
    @Builder.Default
    private BigDecimal holidayOtHours = BigDecimal.ZERO;

    @Column(name = "hourly_rate_kobo", nullable = false)
    @Builder.Default
    private long hourlyRateKobo = 0L;

    @Column(name = "amount_kobo", nullable = false)
    @Builder.Default
    private long amountKobo = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OvertimeStatus status = OvertimeStatus.DRAFT;

    @Column(name = "payroll_adjustment_id")
    private UUID payrollAdjustmentId;

    @Column(name = "computed_at")
    private OffsetDateTime computedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;
}
