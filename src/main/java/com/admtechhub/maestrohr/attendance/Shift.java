package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * A named working shift (start/end time) that an {@link com.admtechhub.maestrohr.employee.Employee}
 * can be assigned to. Tenant-scoped and soft-deleted, matching the {@code LoanPolicy} /
 * {@code PayGrade} pattern.
 */
@Entity
@Table(name = "shifts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class Shift extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "subscriptionPlan", "subscriptionExpiresAt", "isActive"})
    private Tenant tenant;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
