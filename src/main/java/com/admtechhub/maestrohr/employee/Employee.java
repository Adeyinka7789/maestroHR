package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.attendance.Shift;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "employees")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class Employee extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "employee_number", nullable = false, unique = true)
    private String employeeNumber;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status")
    private MaritalStatus maritalStatus;

    @Column(name = "address")
    private String address;

    @Column(name = "nin_encrypted")
    private String ninEncrypted;

    @Column(name = "bvn_encrypted")
    private String bvnEncrypted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pay_grade_id", nullable = false)
    private PayGrade payGrade;

    /** Optional working shift; null means the employee has no shift assigned yet. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "job_title", nullable = false)
    private String jobTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType;

    @Column(name = "employment_start_date", nullable = false)
    private LocalDate employmentStartDate;

    @Column(name = "probation_end_date")
    private LocalDate probationEndDate;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "bank_account_number", nullable = false)
    private String bankAccountNumber;

    @Column(name = "bank_account_name", nullable = false)
    private String bankAccountName;

    @Column(name = "paystack_recipient_code")
    private String paystackRecipientCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    // Probation → Confirmation overlay (see V62). An employee is "on probation" while these
    // are null AND a probationEndDate is set; confirmation stamps both. Kept separate from
    // EmployeeStatus (which stays ACTIVE throughout) so probation staff remain in payroll,
    // headcount and the retirement/birthday sweeps, all of which filter status = ACTIVE.
    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    // Soft-delete marker: when set, the employee is trashed and hidden from scoped
    // reads (see @SQLRestriction) until the cleanup job purges it after the 90-day
    // window. Distinct from status=TERMINATED, which keeps the employee visible.
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "device_enrollment_id", length = 120)
    private String deviceEnrollmentId;

    /**
     * Annual rent paid by the employee (kobo), used for the NTA 2025 PAYE Rent Relief.
     * 0 means no rent declared → no relief. Never null (NOT NULL column, defaulted to 0)
     * so {@link com.admtechhub.maestrohr.payroll.PAYECalculator} never receives a null.
     */
    @Column(name = "annual_rent_paid", nullable = false)
    @Builder.Default
    private Long annualRentPaid = 0L;

    // Helper method to get full name
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // Helper method to check if employee is active
    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }

    /** True once HR has recorded the probation confirmation (see V62). */
    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    /**
     * On probation = a probation window was set, it has not yet been confirmed, and the
     * employee is still on the books (not terminated). Drives the confirmation affordance
     * on the detail page and the pay-grade lock in {@code EmployeeService.updateEmployee}.
     */
    public boolean isOnProbation() {
        return probationEndDate != null
                && confirmedAt == null
                && status != EmployeeStatus.TERMINATED;
    }
}