package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.employee.EmploymentType;
import com.admtechhub.maestrohr.employee.Gender;
import com.admtechhub.maestrohr.employee.MaritalStatus;
import jakarta.validation.constraints.*;
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
public class EmployeeRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email is required")
    @Size(max = 255)
    private String email;

    @NotBlank(message = "Phone is required")
    @Size(max = 20)
    private String phone;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @NotNull(message = "Gender is required")
    private Gender gender;

    @NotNull(message = "Marital status is required")
    private MaritalStatus maritalStatus;

    @NotBlank(message = "Address is required")
    private String address;

    private String nin;
    private String bvn;

    @NotNull(message = "Department ID is required")
    private UUID departmentId;

    @NotNull(message = "Pay grade ID is required")
    private UUID payGradeId;

    @NotBlank(message = "Job title is required")
    @Size(max = 150)
    private String jobTitle;

    @NotNull(message = "Employment type is required")
    private EmploymentType employmentType;

    @NotNull(message = "Employment start date is required")
    private LocalDate employmentStartDate;

    private LocalDate probationEndDate;

    @NotBlank(message = "Bank name is required")
    @Size(max = 100)
    private String bankName;

    @NotBlank(message = "Bank account number is required")
    @Size(max = 20)
    private String bankAccountNumber;

    @NotBlank(message = "Bank account name is required")
    @Size(max = 200)
    private String bankAccountName;

    // New field: Employee's login password
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;
}