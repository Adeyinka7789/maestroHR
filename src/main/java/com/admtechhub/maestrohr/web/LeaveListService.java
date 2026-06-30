package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link LeaveListView} for the redesigned leave page
 * (the HR-admin / manager approval-queue + history table). A single
 * {@link LeaveRequestRepository#findFiltered} call backs the table and its
 * status/search filters, and a second grouped count query
 * ({@link LeaveRequestRepository#countByStatusForTenant}) feeds the filter chips, so
 * the page renders fully populated with no client-side fetches. Mirrors
 * {@link DepartmentListService} / {@link EmployeeListService}.
 */
@Service
@RequiredArgsConstructor
public class LeaveListService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    /** Statuses shown as filter chips, in display order. Excludes CANCELLED (no UI to create it). */
    private static final List<LeaveStatus> CHIP_STATUSES =
            List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED, LeaveStatus.REJECTED);

    /** Upper bound on employees loaded into the Apply form's picker (no pagination on this form). */
    private static final int MAX_EMPLOYEES = 2000;

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeService employeeService;
    private final LeaveService leaveService;

    /**
     * Table-only view (the chip strip + table swap target). Leaves the Apply-form fields
     * empty/false since the {@code table} fragment doesn't reference them — used by the
     * search/chip swaps and the approve/reject re-renders.
     */
    @Transactional(readOnly = true)
    public LeaveListView buildList(String search, LeaveStatus status) {
        UUID tenantId = currentTenantId();
        boolean employeeRole = isEmployeeRole();
        UUID currentEmployeeId = null;
        if (employeeRole) {
            EmployeeDetailsDTO self = currentEmployeeOrNull();
            currentEmployeeId = self != null ? self.getId() : null;
        }
        return assemble(tenantId, search, status, List.of(), List.of(), currentEmployeeId, employeeRole);
    }

    /**
     * Full {@code content} view, including the Apply-for-Leave form's pickers. For a
     * self-service EMPLOYEE the employee picker is locked to their own record (single
     * option + {@code isEmployeeRole}=true); anyone else gets the full tenant roster.
     * Used by the initial page render and the post-submit re-render.
     */
    @Transactional(readOnly = true)
    public LeaveListView buildContent(String search, LeaveStatus status) {
        UUID tenantId = currentTenantId();
        boolean employeeRole = isEmployeeRole();
        EmployeeDetailsDTO self = employeeRole ? currentEmployeeOrNull() : null;
        UUID currentEmployeeId = self != null ? self.getId() : null;

        List<LeaveListView.EmployeeOption> employees;
        if (employeeRole) {
            // Lock the picker to the authenticated employee (one option, or none if no profile).
            employees = self != null
                    ? List.of(new LeaveListView.EmployeeOption(self.getId(), self.getFullName()))
                    : List.of();
        } else {
            employees = loadEmployees(tenantId);
        }

        return assemble(tenantId, search, status, employees, loadLeaveTypes(),
                currentEmployeeId, employeeRole);
    }

    private LeaveListView assemble(UUID tenantId, String search, LeaveStatus status,
                                   List<LeaveListView.EmployeeOption> employees,
                                   List<LeaveListView.LeaveTypeOption> leaveTypes,
                                   UUID currentEmployeeId, boolean isEmployeeRole) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<LeaveRequest> results =
                leaveRequestRepository.findFiltered(tenantId, status, normalizedSearch);

        boolean isDeptMgr = isDeptManagerRole();
        UUID deptId = isDeptMgr ? currentManagerDepartmentId() : null;

        if (isEmployeeRole && currentEmployeeId != null) {
            UUID empId = currentEmployeeId;
            results = results.stream()
                    .filter(r -> r.getEmployee().getId().equals(empId))
                    .toList();
        } else if (isDeptMgr && deptId != null) {
            UUID finalDeptId = deptId;
            results = results.stream()
                    .filter(r -> r.getEmployee().getDepartment() != null
                            && finalDeptId.equals(r.getEmployee().getDepartment().getId()))
                    .toList();
        }

        List<LeaveListView.Row> rows = results.stream().map(this::toRow).toList();

        boolean isRestricted = (isEmployeeRole && currentEmployeeId != null)
                || (isDeptMgr && deptId != null);
        List<LeaveListView.StatusChip> chips = isRestricted
                ? buildChipsFromList(tenantId, status, isEmployeeRole, currentEmployeeId, deptId)
                : buildChips(tenantId, status);

        return new LeaveListView(
                rows,
                rows.size(),
                normalizedSearch,
                status == null ? null : status.name(),
                chips,
                employees,
                leaveTypes,
                currentEmployeeId,
                isEmployeeRole,
                isHrRole());
    }

    // ── Apply-form pickers ───────────────────────────────────────────────────────

    /** Tenant roster for the Apply form's employee dropdown, name-sorted; mirrors the attendance picker. */
    private List<LeaveListView.EmployeeOption> loadEmployees(UUID tenantId) {
        return employeeRepository
                .findAllByTenantId(tenantId, PageRequest.of(0, MAX_EMPLOYEES, Sort.by("firstName", "lastName")))
                .map(e -> new LeaveListView.EmployeeOption(e.getId(), optionLabel(e)))
                .getContent();
    }

    private String optionLabel(Employee e) {
        String number = e.getEmployeeNumber();
        return (number == null || number.isBlank())
                ? e.getFullName()
                : e.getFullName() + " (" + number + ")";
    }

    /** Leave types for the Apply form's type dropdown (tenant-scoped via the shared service query). */
    private List<LeaveListView.LeaveTypeOption> loadLeaveTypes() {
        return leaveService.getAllLeaveTypes().stream()
                .map(t -> new LeaveListView.LeaveTypeOption(t.getId(), t.getName()))
                .toList();
    }

    /** True when the authenticated user holds the EMPLOYEE role (the self-service surface). */
    private boolean isEmployeeRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EMPLOYEE".equals(a.getAuthority()));
    }

    /** True when the authenticated user holds an approver role (HR / manager / finance / superadmin). */
    private boolean isHrRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(a ->
                "ROLE_HR_ADMIN".equals(a)
                        || "ROLE_DEPT_MANAGER".equals(a)
                        || "ROLE_FINANCE_OFFICER".equals(a)
                        || "ROLE_SUPER_ADMIN".equals(a));
    }

    private boolean isDeptManagerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_DEPT_MANAGER".equals(a.getAuthority()));
    }

    private UUID currentManagerDepartmentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return employeeRepository.findByEmail(auth.getName())
                .map(Employee::getDepartment)
                .map(Department::getId)
                .orElse(null);
    }

    /** Resolve the authenticated user's own Employee record, or null for admin/owner accounts with no profile. */
    private EmployeeDetailsDTO currentEmployeeOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? null : employeeService.findByEmail(auth.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── chips ──────────────────────────────────────────────────────────────────

    /** Tenant-wide per-status counts (full data set, not the filtered view) → All + per-status chips. */
    private List<LeaveListView.StatusChip> buildChips(UUID tenantId, LeaveStatus active) {
        Map<LeaveStatus, Long> counts = new EnumMap<>(LeaveStatus.class);
        long total = 0;
        for (Object[] row : leaveRequestRepository.countByStatusForTenant(tenantId)) {
            LeaveStatus s = (LeaveStatus) row[0];
            long c = (Long) row[1];
            counts.put(s, c);
            total += c; // total includes CANCELLED, which has no chip of its own
        }

        List<LeaveListView.StatusChip> chips = new ArrayList<>(CHIP_STATUSES.size() + 1);
        chips.add(new LeaveListView.StatusChip("", "All", total, active == null));
        for (LeaveStatus s : CHIP_STATUSES) {
            chips.add(new LeaveListView.StatusChip(
                    s.name(), humanize(s.name()), counts.getOrDefault(s, 0L), s == active));
        }
        return chips;
    }

    /**
     * Chip counts scoped to a single employee or department: fetches all statuses (no status
     * filter) so each chip shows the true total for that scope, regardless of which chip is active.
     */
    private List<LeaveListView.StatusChip> buildChipsFromList(UUID tenantId, LeaveStatus active,
                                                              boolean isEmployeeRole, UUID currentEmployeeId,
                                                              UUID deptId) {
        List<LeaveRequest> allForScope = leaveRequestRepository.findFiltered(tenantId, null, null);
        if (isEmployeeRole && currentEmployeeId != null) {
            UUID empId = currentEmployeeId;
            allForScope = allForScope.stream()
                    .filter(r -> r.getEmployee().getId().equals(empId))
                    .toList();
        } else if (deptId != null) {
            UUID finalDeptId = deptId;
            allForScope = allForScope.stream()
                    .filter(r -> r.getEmployee().getDepartment() != null
                            && finalDeptId.equals(r.getEmployee().getDepartment().getId()))
                    .toList();
        }
        Map<LeaveStatus, Long> counts = new EnumMap<>(LeaveStatus.class);
        long total = 0;
        for (LeaveRequest r : allForScope) {
            if (r.getStatus() != null) {
                counts.merge(r.getStatus(), 1L, Long::sum);
            }
            total++;
        }
        List<LeaveListView.StatusChip> chips = new ArrayList<>(CHIP_STATUSES.size() + 1);
        chips.add(new LeaveListView.StatusChip("", "All", total, active == null));
        for (LeaveStatus s : CHIP_STATUSES) {
            chips.add(new LeaveListView.StatusChip(
                    s.name(), humanize(s.name()), counts.getOrDefault(s, 0L), s == active));
        }
        return chips;
    }

    // ── row mapping ──────────────────────────────────────────────────────────────

    private LeaveListView.Row toRow(LeaveRequest r) {
        String statusName = r.getStatus() != null ? r.getStatus().name() : LeaveStatus.PENDING.name();
        return new LeaveListView.Row(
                r.getId(),
                r.getEmployee().getFullName(),
                initials(r.getEmployee().getFirstName(), r.getEmployee().getLastName()),
                r.getLeaveType().getName(),
                formatDate(r.getStartDate()),
                formatDate(r.getEndDate()),
                r.getDaysRequested() != null ? r.getDaysRequested() : 0,
                r.getReason() != null ? r.getReason() : "",
                statusName,
                humanize(statusName),
                statusKind(statusName),
                r.getCreatedAt() == null ? "—" : r.getCreatedAt().format(DATE_FORMAT));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the dashboard/employees success/warn/error/neutral scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "APPROVED" -> "success";
            case "PENDING" -> "warn";
            case "REJECTED" -> "error";
            default -> "neutral"; // CANCELLED
        };
    }

    private String formatDate(LocalDate date) {
        return date == null ? "—" : date.format(DATE_FORMAT);
    }

    private String initials(String first, String last) {
        char a = first != null && !first.isBlank() ? first.charAt(0) : '?';
        char b = last != null && !last.isBlank() ? last.charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    /** Turn "PENDING" into "Pending". */
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
