package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * JPQL constructor-projection row backing the redesigned pay-grades list
 * (server-rendered fragment, Option 3). One row per active pay grade with its
 * salary components (in kobo) and the headcount currently assigned to it via the
 * {@code Employee.payGrade} relationship.
 *
 * Gross salary, currency formatting and the summary stats (total / highest /
 * average gross) are derived in the view-model layer, not here. Mirrors
 * {@link DepartmentDTO}, which carries the analogous employee count for departments.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayGradeListRow {
    private UUID id;
    private String name;
    private Long basicSalary;
    private Long housingAllowance;
    private Long transportAllowance;
    private Long otherAllowances;
    private Boolean isActive;
    private Long employeeCount;
    private UUID loanPolicyId;
    private UUID attendancePolicyId;
}
