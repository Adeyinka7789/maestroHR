package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayGradeDTO {
    private UUID id;
    private String name;
    private Long basicSalary;
    private Long housingAllowance;
    private Long transportAllowance;
    private Long otherAllowances;
    private Boolean isActive;
    private Long grossSalary;
}