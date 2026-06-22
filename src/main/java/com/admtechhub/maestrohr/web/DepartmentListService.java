package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.DepartmentDTO;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link DepartmentListView} for the redesigned
 * departments page. {@link DepartmentRepository#findFilteredWithEmployeeCount} backs
 * the list and its optional name search; a second tenant-roster query, grouped by
 * department in memory, supplies each row's Head-of-Department picker options. Both
 * the {@code content} and {@code table} fragments need the per-department roster
 * (the HOD dropdown is populated client-side from each row's {@code data-employees}
 * JSON when Edit is clicked), so there is a single build path. Mirrors
 * {@link EmployeeListService}.
 */
@Service
@RequiredArgsConstructor
public class DepartmentListService {

    private static final DateTimeFormatter CREATED_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    /** Upper bound on employees loaded for the HOD pickers (no pagination on the modal). */
    private static final int MAX_EMPLOYEES = 2000;

    private static final Comparator<Employee> BY_NAME =
            Comparator.comparing(Employee::getFirstName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER);

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DepartmentListView buildList(String search) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<DepartmentDTO> results =
                departmentRepository.findFilteredWithEmployeeCount(tenantId, normalizedSearch);

        Map<UUID, List<DepartmentListView.EmployeeOption>> employeesByDept = employeesByDepartment(tenantId);

        List<DepartmentListView.Row> rows = results.stream()
                .map(d -> toRow(d, employeesByDept))
                .toList();

        return new DepartmentListView(rows, rows.size(), normalizedSearch);
    }

    /**
     * The roster of a single department, name-sorted — used to re-render the HOD
     * dropdown server-side when the create/edit modal reopens on a validation error
     * (the client-side {@code data-employees} path doesn't run on a server re-render).
     */
    @Transactional(readOnly = true)
    public List<DepartmentListView.EmployeeOption> employeesForDepartment(UUID departmentId) {
        return employeeRepository.findByDepartmentId(departmentId).stream()
                .sorted(BY_NAME)
                .map(this::toOption)
                .toList();
    }

    private DepartmentListView.Row toRow(DepartmentDTO d,
                                         Map<UUID, List<DepartmentListView.EmployeeOption>> employeesByDept) {
        List<DepartmentListView.EmployeeOption> deptEmployees =
                employeesByDept.getOrDefault(d.getId(), List.of());
        return new DepartmentListView.Row(
                d.getId(),
                d.getName(),
                d.getEmployeeCount() != null ? d.getEmployeeCount() : 0L,
                formatCreated(d.getCreatedAt()),
                d.getHeadEmployeeId(),
                toJson(deptEmployees));
    }

    /**
     * One tenant-roster query, grouped by department id in memory (avoids an N+1 of
     * one query per department). Employees are name-sorted; the FK is non-null, and
     * reading the lazy department's id needs no extra fetch.
     */
    private Map<UUID, List<DepartmentListView.EmployeeOption>> employeesByDepartment(UUID tenantId) {
        List<Employee> all = employeeRepository
                .findAllByTenantId(tenantId, PageRequest.of(0, MAX_EMPLOYEES, Sort.by("firstName", "lastName")))
                .getContent();

        Map<UUID, List<DepartmentListView.EmployeeOption>> byDept = new HashMap<>();
        for (Employee e : all) {
            if (e.getDepartment() == null) {
                continue;
            }
            byDept.computeIfAbsent(e.getDepartment().getId(), k -> new ArrayList<>())
                    .add(toOption(e));
        }
        return byDept;
    }

    private DepartmentListView.EmployeeOption toOption(Employee e) {
        return new DepartmentListView.EmployeeOption(e.getId(), optionLabel(e));
    }

    private String optionLabel(Employee e) {
        String number = e.getEmployeeNumber();
        return (number == null || number.isBlank())
                ? e.getFullName()
                : e.getFullName() + " (" + number + ")";
    }

    /** Serialize a department's options for the row's data-employees attribute; never fails the page. */
    private String toJson(List<DepartmentListView.EmployeeOption> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String formatCreated(OffsetDateTime createdAt) {
        return createdAt == null ? "—" : createdAt.format(CREATED_FORMAT);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
