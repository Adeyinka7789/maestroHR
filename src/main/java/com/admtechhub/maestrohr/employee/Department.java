package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "departments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class Department extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "subscriptionPlan", "subscriptionExpiresAt", "isActive"})
    private Tenant tenant;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "head_employee_id")
    private String headEmployeeId;

    // Soft-delete marker: when set, the row is trashed and hidden from scoped reads
    // (see @SQLRestriction) until the cleanup job purges it after the 90-day window.
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    // Helper method
    public String getDisplayName() {
        return name;
    }
}