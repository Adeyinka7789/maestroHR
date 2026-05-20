package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDetailsDTO {
    private UUID id;
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private String gender;
    private String maritalStatus;
    private String address;
    private String nin;
    private String bvn;
    private String jobTitle;
    private String employmentType;
    private String status;
    private LocalDate employmentStartDate;
    private LocalDate probationEndDate;
    private LocalDate terminationDate;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountName;
    private String paystackRecipientCode;

    // Department
    private UUID departmentId;
    private String departmentName;

    // Pay Grade
    private UUID payGradeId;
    private String payGradeName;
    private Long basicSalary;      // from payGrade (in kobo)
    private Long housingAllowance; // from payGrade
    private Long transportAllowance;
    private Long otherAllowances;
    private Long grossSalary;      // computed

    // User (auth) info
    private String userEmail;
    private String userRole;
    private Boolean userActive;

    // Constructor from Employee
    public EmployeeDetailsDTO(Employee employee) {
        this.id = employee.getId();
        this.employeeNumber = employee.getEmployeeNumber();
        this.firstName = employee.getFirstName();
        this.lastName = employee.getLastName();
        this.fullName = employee.getFullName();
        this.email = employee.getEmail();
        this.phone = employee.getPhone();
        this.dateOfBirth = employee.getDateOfBirth();
        this.gender = employee.getGender() != null ? employee.getGender().name() : null;
        this.maritalStatus = employee.getMaritalStatus() != null ? employee.getMaritalStatus().name() : null;
        this.address = employee.getAddress();
        this.nin = employee.getNinEncrypted();   // might be encrypted – adjust if needed
        this.bvn = employee.getBvnEncrypted();
        this.jobTitle = employee.getJobTitle();
        this.employmentType = employee.getEmploymentType() != null ? employee.getEmploymentType().name() : null;
        this.status = employee.getStatus() != null ? employee.getStatus().name() : null;
        this.employmentStartDate = employee.getEmploymentStartDate();
        this.probationEndDate = employee.getProbationEndDate();
        this.terminationDate = employee.getTerminationDate();
        this.bankName = employee.getBankName();
        this.bankAccountNumber = employee.getBankAccountNumber();
        this.bankAccountName = employee.getBankAccountName();
        this.paystackRecipientCode = employee.getPaystackRecipientCode();

        if (employee.getDepartment() != null) {
            this.departmentId = employee.getDepartment().getId();
            this.departmentName = employee.getDepartment().getName();
        }
        if (employee.getPayGrade() != null) {
            this.payGradeId = employee.getPayGrade().getId();
            this.payGradeName = employee.getPayGrade().getName();
            this.basicSalary = employee.getPayGrade().getBasicSalary();
            this.housingAllowance = employee.getPayGrade().getHousingAllowance();
            this.transportAllowance = employee.getPayGrade().getTransportAllowance();
            this.otherAllowances = employee.getPayGrade().getOtherAllowances();
            this.grossSalary = employee.getPayGrade().getGrossSalary();
        }
        if (employee.getUser() != null) {
            this.userEmail = employee.getUser().getEmail();
            this.userRole = employee.getUser().getRole() != null ? employee.getUser().getRole().name() : null;
            this.userActive = employee.getUser().isActive();
        }
    }
}