package com.admtechhub.maestrohr.employee;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
public class EmployeeSummaryDTO {
    private UUID id;
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private String jobTitle;
    private String departmentName;
    private String payGradeName;
    private Long basicSalary;
    private EmployeeStatus status;
    private LocalDate employmentStartDate;
    private LocalDate dateOfBirth;
    private String gender;
    private String maritalStatus;
    private String address;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountName;
    private String paystackRecipientCode;
    private LocalDate createdAt;
    private String employmentType;
    private LocalDate probationEndDate;
    private Integer payrollCount;
    private Integer leaveDaysTaken;

    public EmployeeSummaryDTO(Employee employee) {
        this.id = employee.getId();
        this.employeeNumber = employee.getEmployeeNumber();
        this.firstName = employee.getFirstName();
        this.lastName = employee.getLastName();
        this.fullName = employee.getFullName();
        this.email = employee.getEmail();
        this.phone = employee.getPhone();
        this.jobTitle = employee.getJobTitle();
        this.status = employee.getStatus();
        this.employmentStartDate = employee.getEmploymentStartDate();
        this.dateOfBirth = employee.getDateOfBirth();

        // Enums to string
        this.gender = employee.getGender() != null ? employee.getGender().name() : null;
        this.maritalStatus = employee.getMaritalStatus() != null ? employee.getMaritalStatus().name() : null;
        this.employmentType = employee.getEmploymentType() != null ? employee.getEmploymentType().name() : null;

        // Basic fields
        this.address = employee.getAddress();
        this.bankName = employee.getBankName();
        this.bankAccountNumber = employee.getBankAccountNumber();
        this.bankAccountName = employee.getBankAccountName();
        this.paystackRecipientCode = employee.getPaystackRecipientCode();
        this.probationEndDate = employee.getProbationEndDate();
        this.createdAt = employee.getCreatedAt() != null ? employee.getCreatedAt().toLocalDate() : null;

        // Lazy-loaded relationships (safe)
        if (employee.getDepartment() != null) {
            try {
                this.departmentName = employee.getDepartment().getName();
            } catch (Exception e) {
                this.departmentName = null;
            }
        }
        if (employee.getPayGrade() != null) {
            try {
                this.payGradeName = employee.getPayGrade().getName();
                this.basicSalary = employee.getPayGrade().getBasicSalary();
            } catch (Exception e) {
                this.payGradeName = null;
                this.basicSalary = null;
            }
        }

        // Stats – you may want to fetch these via separate queries (set to 0 for now)
        this.payrollCount = 0;
        this.leaveDaysTaken = 0;
    }
}