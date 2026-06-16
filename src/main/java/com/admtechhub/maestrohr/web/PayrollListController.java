package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunService;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

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
 * SCOPE (Step C): read-only list + status filter + search (Step A) plus the Initiate
 * write — a top-of-page form posting to {@code /htmx/payroll/initiate}. On success it
 * navigates straight to the new DRAFT run's detail page (returning the
 * {@code payroll-detail :: content} fragment and pushing {@code /htmx/payroll/{id}}),
 * because the next logical action — Compute — lives there. A duplicate period (or a bad
 * month) raises {@link IllegalStateException}/{@link IllegalArgumentException}, caught by
 * {@link #handleInitiateFailure} and shown as an in-place banner on the re-rendered list.
 * The remaining writes (compute/submit/approve/reject) live on
 * {@link PayrollDetailController}; disburse remains deferred to Step D.
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
    private final PayrollDetailService payrollDetailService;
    private final PayrollRunService payrollRunService;
    private final UserRepository userRepository;

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
     * Initiate a new DRAFT run for the chosen month, then jump to its detail page so the
     * user can Compute it next. The initiator is resolved server-side (never trusted from
     * the form). {@code period} is the {@code <input type="month">} value ("YYYY-MM"); a
     * blank/malformed value or a duplicate period surfaces as an in-place banner via
     * {@link #handleInitiateFailure}. The {@code HX-Push-Url} header updates the browser
     * URL to the new run so back/forward and refresh resolve to the detail page.
     */
    @PostMapping("/htmx/payroll/initiate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public String initiate(
            @RequestParam("period") String period,
            HttpServletResponse response,
            Model model) {

        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Select a valid payroll month.");
        }

        PayrollRunResponse run =
                payrollRunService.initiatePayroll(ym.getMonthValue(), ym.getYear(), currentUserId());

        response.setHeader("HX-Push-Url", "/htmx/payroll/" + run.getId());
        model.addAttribute("view", payrollDetailService.build(run.getId()));
        model.addAttribute("success",
                "Payroll run " + run.getPeriod() + " created. Compute it to generate entries.");
        return "payroll-detail :: content";
    }

    /**
     * Renders Initiate failures as the in-place list {@code content} fragment with an
     * {@code ${error}} banner instead of a JSON 500 — the common case is the duplicate
     * period ({@link IllegalStateException} "already exists") or a missing/malformed month
     * ({@link IllegalArgumentException}). Returns HTTP 200 so HTMX still swaps; the active
     * search/status (sent with the failed POST) are recovered so the list looks unchanged
     * apart from the banner. Mirrors {@link LeaveListController#handleActionFailure}.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleInitiateFailure(RuntimeException ex, HttpServletRequest request, Model model) {
        String q = request.getParameter("q");
        String status = request.getParameter("status");
        model.addAttribute("view", payrollListService.buildList(q, parseStatus(status)));
        model.addAttribute("error", ex.getMessage());
        return "payroll :: content";
    }

    /** Resolve the authenticated user's id to record as the initiator. Mirrors LeaveListController. */
    private UUID currentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email))
                .getId();
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
