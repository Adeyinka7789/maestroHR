package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payroll_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PayrollRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @Column(name = "payroll_month", nullable = false)
    private Integer payrollMonth;

    @Column(name = "payroll_year", nullable = false)
    private Integer payrollYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PayrollStatus status = PayrollStatus.DRAFT;

    @Column(name = "total_gross", nullable = false)
    @Builder.Default
    private Long totalGross = 0L;

    @Column(name = "total_net", nullable = false)
    @Builder.Default
    private Long totalNet = 0L;

    @Column(name = "total_paye", nullable = false)
    @Builder.Default
    private Long totalPaye = 0L;

    @Column(name = "total_pension_employee", nullable = false)
    @Builder.Default
    private Long totalPensionEmployee = 0L;

    @Column(name = "total_pension_employer", nullable = false)
    @Builder.Default
    private Long totalPensionEmployer = 0L;

    @Column(name = "total_nhf", nullable = false)
    @Builder.Default
    private Long totalNhf = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by", nullable = false)
    @JsonIgnoreProperties({"passwordHash", "failedLoginAttempts", "lockedUntil", "lastLoginAt"})
    private User initiatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    @JsonIgnoreProperties({"passwordHash", "failedLoginAttempts", "lockedUntil", "lastLoginAt"})
    private User approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @OneToMany(mappedBy = "payrollRun", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PayrollEntry> entries = new ArrayList<>();

    // Helper methods
    public String getPeriod() {
        return String.format("%d-%02d", payrollYear, payrollMonth);
    }

    public boolean isEditable() {
        return status == PayrollStatus.DRAFT;
    }

    public boolean canSubmit() {
        return status == PayrollStatus.DRAFT;
    }

    public boolean canApprove() {
        return status == PayrollStatus.PENDING_APPROVAL;
    }

    public boolean canReject() {
        return status == PayrollStatus.PENDING_APPROVAL;
    }
}