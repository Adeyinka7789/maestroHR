package com.admtechhub.maestrohr.payroll.dto;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PayrollRunResponse {
    private UUID id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private TenantDto tenant;
    private Integer payrollMonth;
    private Integer payrollYear;
    private PayrollStatus status;
    private Long totalGross;
    private Long totalNet;
    private Long totalPaye;
    private Long totalPensionEmployee;
    private Long totalPensionEmployer;
    private Long totalNhf;
    private UserDto initiatedBy;
    private UserDto approvedBy;
    private LocalDateTime approvedAt;
    private String rejectionReason;
    private List<PayrollEntryResponse> entries;
    private String period;
    private boolean editable;

    @Data
    @Builder
    public static class TenantDto {
        private UUID id;
        private String companyName;
    }

    @Data
    @Builder
    public static class UserDto {
        private UUID id;
        private String email;
        private String role;
    }

    @Data
    @Builder
    public static class PayrollEntryResponse {
        private UUID id;
        private Long basicSalary;
        private Long housingAllowance;
        private Long transportAllowance;
        private Long otherAllowances;
        private Long grossSalary;
        private Long pensionEmployee;
        private Long pensionEmployer;
        private Long nhfDeduction;
        private Long payeTax;
        private Long netSalary;
        private String employeeName;
        private String employeeNumber;
    }
}