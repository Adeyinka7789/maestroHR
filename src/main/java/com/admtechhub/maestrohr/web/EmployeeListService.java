package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link EmployeeListView} for the redesigned
 * employees page. A single {@link EmployeeRepository#findFiltered} call backs the
 * list and every search/department/status filter combination, so the table renders
 * fully populated with no client-side fetches.
 */
@Service
@RequiredArgsConstructor
public class EmployeeListService {

    static final int PAGE_SIZE = 20;

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public EmployeeListView buildList(String search, UUID departmentId, EmployeeStatus status, int page) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        int safePage = Math.max(page, 0);

        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, Sort.by("lastName").ascending());
        Page<Employee> result =
                employeeRepository.findFiltered(tenantId, normalizedSearch, departmentId, status, pageable);

        List<EmployeeListView.Row> rows = result.getContent().stream().map(this::toRow).toList();

        long total = result.getTotalElements();
        int from = total == 0 ? 0 : result.getNumber() * PAGE_SIZE + 1;
        int to = result.getNumber() * PAGE_SIZE + result.getNumberOfElements();

        List<EmployeeListView.DepartmentOption> departments =
                departmentRepository.findAllByTenantId(tenantId).stream()
                        .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                        .map(d -> new EmployeeListView.DepartmentOption(
                                d.getId(), d.getName(), d.getId().equals(departmentId)))
                        .toList();

        List<EmployeeListView.StatusOption> statuses =
                Arrays.stream(EmployeeStatus.values())
                        .map(s -> new EmployeeListView.StatusOption(
                                s.name(), humanize(s.name()), s.equals(status)))
                        .toList();

        return new EmployeeListView(
                rows, total, result.getNumber(), result.getTotalPages(),
                result.hasPrevious(), result.hasNext(), from, to,
                normalizedSearch, departmentId, status, departments, statuses);
    }

    private EmployeeListView.Row toRow(Employee e) {
        String departmentName = e.getDepartment() != null ? e.getDepartment().getName() : "—";
        String payGradeName = e.getPayGrade() != null ? e.getPayGrade().getName() : "—";
        Long basicSalary = e.getPayGrade() != null ? e.getPayGrade().getBasicSalary() : null;
        String statusName = e.getStatus() != null ? e.getStatus().name() : EmployeeStatus.ACTIVE.name();

        return new EmployeeListView.Row(
                e.getId(),
                initials(e),
                e.getFullName(),
                e.getEmail(),
                e.getEmployeeNumber() != null ? e.getEmployeeNumber() : "—",
                departmentName,
                payGradeName,
                statusName,
                statusKind(statusName),
                formatSalary(basicSalary));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the dashboard's success/warn/error/neutral scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "ON_LEAVE" -> "warn";
            case "SUSPENDED" -> "error";
            default -> "neutral"; // TERMINATED
        };
    }

    /** Pay-grade basic salary is stored in kobo; render whole naira with thousands separators. */
    private String formatSalary(Long kobo) {
        if (kobo == null) {
            return "—";
        }
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    private String initials(Employee e) {
        char a = e.getFirstName() != null && !e.getFirstName().isBlank() ? e.getFirstName().charAt(0) : '?';
        char b = e.getLastName() != null && !e.getLastName().isBlank() ? e.getLastName().charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
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

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
