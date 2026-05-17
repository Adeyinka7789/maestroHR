package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "departments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Department extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "subscriptionPlan", "subscriptionExpiresAt", "isActive"})
    private Tenant tenant;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "head_employee_id")
    private String headEmployeeId;

    // Helper method
    public String getDisplayName() {
        return name;
    }
}