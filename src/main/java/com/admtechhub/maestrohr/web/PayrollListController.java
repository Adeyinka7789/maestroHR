package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Payroll-runs list route (Option 3 — server-rendered fragment), mirroring the
 * leave / attendance / departments pages. The HTMX request renders
 * templates/payroll.html with the run history / approval queue already in the markup
 * (no /api/payroll double-fetch, no loading flash); a non-HTMX visit returns the static
 * layout shell and layout.js re-requests this route with the HX-Request header.
 *
 * This is the HR-admin / finance-facing run list. A status filter (All / Draft /
 * Pending Approval / Approved / Disbursing / Completed / Rejected) plus a free-text
 * search are driven by {@code /htmx/payroll/table}, which swaps the chip strip + table
 * fragment.
 *
 * SCOPE (Step A): read-only list + status filter + search. The write workflows —
 * initiate, compute, submit, approve, reject, disburse — are deferred to later,
 * separately-reviewed steps because they move money; static/payroll.html remains on
 * disk as the legacy fallback until this fragment is browser-verified.
 *
 * Gated to {@code HR_ADMIN / FINANCE_OFFICER / SUPER_ADMIN} — the same roles the REST
 * {@code GET /api/payroll} list and the initiate gate use; these roles see the full
 * tenant-wide run list (DEPT_MANAGER is intentionally excluded here).
 *
 * NOTE: named {@code PayrollListController} (not {@code PayrollController}) on purpose —
 * the REST API controller {@link com.admtechhub.maestrohr.payroll.PayrollController}
 * already owns the default {@code payrollController} bean name; a second bean with that
 * name would fail context startup.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
public class PayrollListController {

    private final PayrollListService payrollListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/payroll")
    public String payroll(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view", payrollListService.buildList(q, parseStatus(status)));
        return "payroll :: content";
    }

    /** Chip strip + table only — the swap target for search and the status chips. */
    @GetMapping("/htmx/payroll/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        model.addAttribute("view", payrollListService.buildList(q, parseStatus(status)));
        return "payroll :: table";
    }

    /**
     * Maps the status query param to a {@link PayrollStatus} filter:
     *   - absent (null) / blank ("") → null = the "All" chip (the run-history landing view);
     *   - valid name                 → that status;
     *   - unknown value              → null = All (show everything rather than silently hide data).
     */
    private PayrollStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PayrollStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
