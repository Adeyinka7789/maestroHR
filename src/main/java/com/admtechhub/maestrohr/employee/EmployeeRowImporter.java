package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Imports a single parsed row in its <strong>own</strong> transaction.
 *
 * <p>This lives in a separate bean from {@link EmployeeImportService} on purpose: Spring's
 * {@code @Transactional} is proxy-based, so {@link Propagation#REQUIRES_NEW} only takes effect when
 * the method is invoked through the proxy (i.e. from another bean). Calling it from within
 * {@code EmployeeImportService} via self-invocation would silently run in the caller's transaction
 * and reintroduce the exact "one bad row rolls back the batch" bug this rebuild fixes.
 *
 * <p>Because each call gets a fresh connection from the pool, the RLS tenant GUC is re-applied from
 * {@link TenantContext} (see {@code DataSourceConfig}); the import runs on a single thread, so the
 * tenant context is intact for every row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmployeeRowImporter {

    private final EmployeeService employeeService;
    private final TenantRepository tenantRepository;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;

    /** Placeholder applied when a NOT-NULL field is left blank in the file (see EmployeeImportService warnings). */
    private static final LocalDate DEFAULT_DOB = LocalDate.of(2000, 1, 1);

    /**
     * Persist one row. Runs in a new, independent transaction so a failure here rolls back only
     * this row — never a previously-committed one.
     *
     * @throws RuntimeException with a human-readable message when the row cannot be imported.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void importRow(EmployeeImportService.ParsedRow row, boolean createMissingDepartments) {
        UUID tenantId = currentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found"));

        Department department = resolveDepartment(tenantId, tenant, row.departmentName, createMissingDepartments);
        PayGrade payGrade = resolvePayGrade(tenantId, row.payGradeName);

        EmployeeRequest request = EmployeeRequest.builder()
                .firstName(row.firstName)
                .lastName(nullToEmpty(row.lastName))
                .email(row.email)
                .phone(nullToEmpty(row.phone))
                .jobTitle(nullToEmpty(row.jobTitle))
                .employmentType(row.employmentType != null ? row.employmentType : EmploymentType.FULL_TIME)
                .departmentId(department.getId())
                .payGradeId(payGrade.getId())
                .bankName(nullToEmpty(row.bankName))
                .bankAccountNumber(nullToEmpty(row.accountNumber))
                .bankAccountName(nullToEmpty(row.accountName))
                .dateOfBirth(row.dateOfBirth != null ? row.dateOfBirth : DEFAULT_DOB)
                .gender(row.gender != null ? row.gender : Gender.MALE)
                .maritalStatus(row.maritalStatus != null ? row.maritalStatus : MaritalStatus.SINGLE)
                .address(nullToEmpty(row.address))
                .employmentStartDate(row.startDate != null ? row.startDate : LocalDate.now())
                .password(generatePassword(row.email))
                .build();

        employeeService.createEmployee(request);
    }

    private Department resolveDepartment(UUID tenantId, Tenant tenant, String name, boolean createMissing) {
        List<Department> existing = departmentRepository.findAllByTenantId(tenantId);
        return existing.stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    if (!createMissing) {
                        throw new IllegalArgumentException("Department '" + name + "' does not exist.");
                    }
                    Department dept = Department.builder().tenant(tenant).name(name).build();
                    return departmentRepository.save(dept);
                });
    }

    /**
     * Resolve a pay grade by name, falling back to the tenant's first configured grade. We
     * deliberately do <em>not</em> fabricate a zero-salary grade: if the organization has no pay
     * grade at all, the row fails with a clear message so the user fixes the root cause.
     */
    private PayGrade resolvePayGrade(UUID tenantId, String name) {
        List<PayGrade> grades = payGradeRepository.findAllByTenantId(tenantId);
        if (grades.isEmpty()) {
            throw new IllegalStateException(
                    "No pay grade is configured for this organization — create one before importing.");
        }
        if (name != null) {
            return grades.stream()
                    .filter(g -> g.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(grades.get(0));
        }
        return grades.get(0);
    }

    /** Deterministic first password derived from the email; the welcome email shares it with the hire. */
    private String generatePassword(String email) {
        String local = email == null ? "" : email.split("@")[0];
        String suffix = local.length() >= 4 ? local.substring(local.length() - 4) : local;
        if (suffix.length() < 2) {
            suffix = suffix + "0000";
        }
        return "Welcome@" + suffix;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private UUID currentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantIdStr);
    }
}
