package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.EmploymentType;
import com.admtechhub.maestrohr.employee.Gender;
import com.admtechhub.maestrohr.employee.MaritalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeResponse {
    private UUID id;
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Gender gender;
    private MaritalStatus maritalStatus;
    private String address;
    private String departmentId;
    private String departmentName;
    private String payGradeId;
    private String payGradeName;
    private Long basicSalary; // in kobo
    private String jobTitle;
    private EmploymentType employmentType;
    private LocalDate employmentStartDate;
    private LocalDate probationEndDate;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountName;
    private String paystackRecipientCode;
    private EmployeeStatus status;
    private LocalDate terminationDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}