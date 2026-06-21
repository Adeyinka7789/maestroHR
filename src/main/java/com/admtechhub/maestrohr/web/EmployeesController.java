package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

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
