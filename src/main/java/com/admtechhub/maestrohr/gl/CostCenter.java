package com.admtechhub.maestrohr.gl;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A cost center / branch (e.g. "Lekki Outlet", "Abuja Office") an employee's payroll can be
 * attributed to for GL accounting (see V65). Tenant-scoped and soft-deleted, mirroring
 * {@code Shift} / {@code Department}. Optionally carries its own GL expense account code; when
 * blank the tenant's default salary-expense account is used at export time.
 */
@Entity
@Table(name = "cost_centers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class CostCenter extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "location", length = 120)
    private String location;

    @Column(name = "gl_account_code", length = 60)
    private String glAccountCode;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
