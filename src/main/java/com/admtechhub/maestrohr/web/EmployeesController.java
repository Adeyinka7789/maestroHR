package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Employees list route (Option 3 — server-rendered fragment), mirroring the
 * dashboard pilot. The HTMX request renders templates/employees.html with the
 * table already in the markup (no /api/employees double-fetch, no loading flash);
 * a non-HTMX visit returns the static layout shell and layout.js re-requests this
 * route with the HX-Request header.
 *
 * Search, department, and status filters plus pagination are driven by
 * {@code /htmx/employees/table}, which swaps only the table + pagination fragment.
 *
 * NOTE: this owns only {@code /htmx/employees(/table)}. The bare {@code /employees}
 * route is intentionally still owned by {@link EmployeeWebController}, which serves
 * the legacy static page as a fallback until the new fragment is browser-verified.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
public class EmployeesController {

    private final EmployeeListService employeeListService;
    private final EmployeeDetailService employeeDetailService;
    private final EmployeeService employeeService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/employees")
    public String employees(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "dept", required = false) String dept,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        addView(model, q, dept, status, page);
        return "employees :: content";
    }

    /**
     * Employee detail page (kept on the legacy {@code ?id=} query-param URL for
     * backward compatibility, not a path variable). App shell on a cold visit
     * (layout.js re-requests this route under HTMX); the populated fragment under
     * HTMX. An id that does not belong to the current tenant reads as not-found
     * (hidden by the entity's {@code @SQLRestriction}) and falls back to the list
     * with an error banner. Replaces the old PageController forward to the static
     * employee-view.html.
     */
    @GetMapping("/htmx/employee-view")
    public String employeeDetail(
            @RequestParam("id") UUID id,
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        EmployeeDetailView detail = employeeDetailService.buildDetail(id);
        if (detail == null) {
            model.addAttribute("error", "Employee not found.");
            addView(model, null, null, null, 0);
            return "employees :: content";
        }

        model.addAttribute("detail", detail);
        return "employee-detail :: content";
    }

    /**
     * Terminate (soft delete) the employee from the detail page, then re-render the detail
     * fragment with the status now showing TERMINATED and the Terminate button gone. The
     * date comes from the inline confirm form (defaulting to today when omitted). Mirrors the
     * REST {@code DELETE /api/employees/{id}/terminate} but returns HTML for the HTMX surface.
     * Role gate matches that endpoint (HR_ADMIN/SUPER_ADMIN), overriding the class default.
     */
    @DeleteMapping("/htmx/employee-view/{id}/terminate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String terminate(
            @PathVariable UUID id,
            @RequestParam(value = "terminationDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate terminationDate,
            Model model) {

        employeeService.terminateEmployee(id, terminationDate != null ? terminationDate : LocalDate.now());
        model.addAttribute("detail", employeeDetailService.buildDetail(id));
        return "employee-detail :: content";
    }

    /**
     * Soft-delete the employee — move it to the 90-day trash (SUPER_ADMIN only), allowed by the
     * service only when no records depend on it; otherwise {@link IllegalStateException} bubbles to
     * the handler below, which re-renders the list with the reason. On success the employee is
     * hidden from all scoped views (and can be restored from the super-admin trash page), so we send
     * an {@code HX-Redirect} to bounce the browser to the employees list; the returned empty fragment
     * is discarded by HTMX once it sees that header.
     */
    @DeleteMapping("/htmx/employee-view/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String softDelete(@PathVariable UUID id, HttpServletResponse response) {
        employeeService.softDeleteEmployee(id);
        response.setHeader("HX-Redirect", "/htmx/employees");
        return "employee-detail :: empty";
    }

    /**
     * Action failures (terminate/hard-delete blocked or targeting a missing/foreign id) fall
     * back to the employees list with an error banner, mirroring the not-found handling in
     * {@link #employeeDetail}. Keeps a destructive action that the service refused from leaving
     * the user on a broken page.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleActionFailure(RuntimeException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        addView(model, null, null, null, 0);
        return "employees :: content";
    }

    /** Table body + pagination only — the swap target for search / filter / paging. */
    @GetMapping("/htmx/employees/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "dept", required = false) String dept,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {

        addView(model, q, dept, status, page);
        return "employees :: table";
    }

    private void addView(Model model, String q, String dept, String status, int page) {
        model.addAttribute("view",
                employeeListService.buildList(q, parseUuid(dept), parseStatus(status), page));
    }

    /** Treat blank/invalid query params as "no filter" rather than failing the request. */
    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private EmployeeStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EmployeeStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
