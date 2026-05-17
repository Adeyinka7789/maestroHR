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

        // Safely get department name
        if (employee.getDepartment() != null) {
            try {
                this.departmentName = employee.getDepartment().getName();
            } catch (Exception e) {
                this.departmentName = null;
            }
        }

        // Safely get pay grade name and salary
        if (employee.getPayGrade() != null) {
            try {
                this.payGradeName = employee.getPayGrade().getName();
                this.basicSalary = employee.getPayGrade().getBasicSalary();
            } catch (Exception e) {
                this.payGradeName = null;
                this.basicSalary = null;
            }
        }
    }
}
