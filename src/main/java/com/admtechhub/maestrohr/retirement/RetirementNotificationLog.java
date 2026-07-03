package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * Records that HR has already been notified about a given employee crossing a given
 * retirement notification threshold, so {@link RetirementNotificationJob} never re-fires
 * the same (employee, thresholdDays) pair on a later daily run. The unique constraint on
 * (employee_id, threshold_days) in the migration makes this idempotent even under a
 * race/double-run of the job.
 */
@Entity
@Table(name = "retirement_notification_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class RetirementNotificationLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @Column(name = "threshold_days", nullable = false)
    private Integer thresholdDays;

    @Column(name = "notified_at", nullable = false)
    private OffsetDateTime notifiedAt;
}
