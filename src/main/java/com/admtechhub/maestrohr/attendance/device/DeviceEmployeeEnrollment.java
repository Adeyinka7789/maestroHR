package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

@Entity
@Table(name = "device_employee_enrollments",
        uniqueConstraints = @UniqueConstraint(name = "uk_enrollment_device_emp",
                columnNames = {"device_api_key_id", "device_employee_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class DeviceEmployeeEnrollment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_api_key_id", nullable = false)
    private DeviceApiKey deviceApiKey;

    @Column(name = "device_employee_id", nullable = false, length = 100)
    private String deviceEmployeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "enrolled_at", nullable = false)
    @Builder.Default
    private OffsetDateTime enrolledAt = OffsetDateTime.now();
}
