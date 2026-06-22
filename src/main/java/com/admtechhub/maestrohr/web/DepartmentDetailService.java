package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link DepartmentDetailView} for the department
 * detail page. One {@link DepartmentRepository#findById} (tenant-scoped via the
 * {@code @SQLRestriction} on {@link Department}) plus one
 * {@link EmployeeRepository#findByDepartmentId} call back the whole page, so it
 * renders fully populated with no client-side fetches. Mirrors
 * {@link DepartmentListService} / {@link EmployeeListService}.
 */
@Service
@RequiredArgsConstructor
public class DepartmentDetailService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    /**
     * Builds the detail view for {@code departmentId}, or {@code null} if no such
     * department exists for the current tenant (the {@code @SQLRestriction} hides
     * other tenants' rows, so a foreign id reads as not-found).
     */
    @Transactional(readOnly = true)
    public DepartmentDetailView buildDetail(UUID departmentId) {
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return null;
        }

        List<Employee> employees = employeeRepository.findByDepartmentId(departmentId);

        long total = employees.size();
        long active = employees.stream()
                .filter(e -> e.getStatus() == EmployeeStatus.ACTIVE)
                .count();
        long onLeave = employees.stream()
                .filter(e -> e.getStatus() == EmployeeStatus.ON_LEAVE)
                .count();

        // Monthly payroll cost: pay-grade gross salary summed over ACTIVE employees only
        // (kobo). Not derived from any payroll run — this is current establishment cost.
        long monthlyPayrollKobo = employees.stream()
                .filter(e -> e.getStatus() == EmployeeStatus.ACTIVE)
                .map(Employee::getPayGrade)
                .filter(Objects::nonNull)
                .mapToLong(pg -> pg.getGrossSalary() != null ? pg.getGrossSalary() : 0L)
                .sum();

        List<DepartmentDetailView.Row> rows = employees.stream()
                .sorted(Comparator.comparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Employee::getFirstName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toRow)
                .toList();

        return new DepartmentDetailView(
                dept.getId(),
                dept.getName(),
                resolveHeadName(dept.getHeadEmployeeId()),
                formatDate(dept.getCreatedAt()),
                total, active, onLeave,
                formatNaira(monthlyPayrollKobo),
                rows);
    }

    /**
     * Resolve the stored head employee id (UUID string) to a display name, or
     * "Not assigned" when unset. A blank, malformed, or stale id (employee deleted)
     * also falls back to "Not assigned" rather than failing the page.
     */
    private String resolveHeadName(String headEmployeeId) {
        if (headEmployeeId == null || headEmployeeId.isBlank()) {
            return "Not assigned";
        }
        try {
            return employeeRepository.findById(UUID.fromString(headEmployeeId.trim()))
                    .map(Employee::getFullName)
                    .orElse("Not assigned");
        } catch (IllegalArgumentException ex) {
            return "Not assigned";
        }
    }

    private DepartmentDetailView.Row toRow(Employee e) {
        String payGradeName = e.getPayGrade() != null ? e.getPayGrade().getName() : "—";
        String jobTitle = e.getJobTitle() != null ? e.getJobTitle() : "—";
        String statusName = e.getStatus() != null ? e.getStatus().name() : EmployeeStatus.ACTIVE.name();

        return new DepartmentDetailView.Row(
                e.getId(),
                e.getFullName(),
                jobTitle,
                payGradeName,
                formatDate(e.getEmploymentStartDate()),
                statusName,
                humanize(statusName),
                statusKind(statusName));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the employees list scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "ON_LEAVE" -> "warn";
            case "SUSPENDED" -> "error";
            default -> "neutral"; // TERMINATED
        };
    }

    /** Pay-grade salaries are stored in kobo; render whole naira with thousands separators. */
    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    private String formatDate(OffsetDateTime value) {
        return value == null ? "—" : value.format(DATE_FORMAT);
    }

    private String formatDate(LocalDate value) {
        return value == null ? "—" : value.format(DATE_FORMAT);
    }

    /** Turn "ON_LEAVE" into "On Leave". */
    private String humanize(String raw) {
        String spaced = raw.replace('_', ' ').trim().toLowerCase(Locale.ENGLISH);
        StringBuilder sb = new StringBuilder(spaced.length());
        boolean cap = true;
        for (char c : spaced.toCharArray()) {
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
