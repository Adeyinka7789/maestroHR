package com.admtechhub.maestrohr.adjustment;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A one-off adjustment logged against an employee for a specific month (e.g. a ₦5,000 late fine
 * for July 2026). PENDING items are consumed by the next payroll run for that period; when a run
 * applies them they flip to APPLIED and record the {@code payrollRunId}. Reversing that run flips
 * them back to PENDING. Amount is always positive kobo; the {@link AdjustmentType}'s direction
 * decides whether it adds to or subtracts from pay.
 */
@Entity
@Table(name = "payroll_adjustments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class PayrollAdjustment extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "adjustment_type_id", nullable = false)
    private UUID adjustmentTypeId;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AdjustmentStatus status = AdjustmentStatus.PENDING;

    @Column(name = "payroll_run_id")
    private UUID payrollRunId;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "created_by")
    private String createdBy;
}
