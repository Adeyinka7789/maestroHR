package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Payroll-run detail route (Option 3 — server-rendered fragment). The HTMX request
 * renders templates/payroll-detail.html with the statutory summary + per-employee
 * breakdown already in the markup (no /api/payroll/{id} double-fetch, no loading flash);
 * a non-HTMX visit returns the static layout shell and layout.js re-requests this route
 * with the HX-Request header.
 *
 * SCOPE (Step C): read-only detail (Step B) plus the lifecycle write actions —
 * compute / submit / approve / reject. Each posts to {@code /htmx/payroll/{id}/…} and
 * re-renders the whole {@code content} fragment so the summary cards, entries table, and
 * action-button row all reflect the new status. These transitions change status/figures
 * only; money movement (disburse) remains deferred to Step D. Wrong-state transitions
 * (the stale-page / double-click case) raise {@link IllegalStateException} from the
 * service and are rendered as an in-place {@code ${error}} banner via {@link #renderWithError}
 * (HTTP 200, so HTMX still swaps) rather than the JSON {@code GlobalExceptionHandler}.
 *
 * Each write endpoint carries its own method-level {@link PreAuthorize} gate (narrower
 * than the class gate): submit is HR_ADMIN/SUPER_ADMIN; approve is FINANCE_OFFICER/
 * SUPER_ADMIN (segregation of duties — the initiator cannot approve); compute and reject
 * follow the initiate gate (HR_ADMIN/FINANCE_OFFICER/SUPER_ADMIN), matching their REST
 * endpoints. The matching {@code show*} flags on
 * {@link PayrollDetailView} hide buttons the viewer's role can't use, so the gate is
 * defence-in-depth, not the only check.
 *
 * Gated to {@code HR_ADMIN / FINANCE_OFFICER / SUPER_ADMIN} — the same roles as the run
 * list (the REST detail endpoint additionally allows DEPT_MANAGER, but this HR/finance
 * web view deliberately matches the list's narrower gate).
 *
 * NOTE: named {@code PayrollDetailController} (not {@code PayrollController}) on purpose —
 * the REST API controller {@link com.admtechhub.maestrohr.payroll.PayrollController}
 * already owns the default {@code payrollController} bean name.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
public class PayrollDetailController {

    private final PayrollDetailService payrollDetailService;
    private final PayrollRunService payrollRunService;
    private final UserRepository userRepository;

    /** Full page: app shell on a cold visit, the populated detail fragment under HTMX. */
    @GetMapping("/htmx/payroll/{id}")
    public String detail(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @PathVariable UUID id,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view", payrollDetailService.build(id));
        return "payroll-detail :: content";
    }

    /**
     * Compute (or recompute) a DRAFT run's entries, then re-render the detail so the
     * summary cards and breakdown populate. Recompute replaces the existing figures; the
     * template gates that behind a confirm. A non-DRAFT run raises
     * {@link IllegalStateException} → in-place error banner.
     */
    @PostMapping("/htmx/payroll/{id}/compute")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public String compute(@PathVariable UUID id, Model model) {
        try {
            payrollRunService.computePayroll(id);
        } catch (IllegalStateException ex) {
            return renderWithError(id, ex.getMessage(), model);
        }
        model.addAttribute("view", payrollDetailService.build(id));
        model.addAttribute("success", "Payroll computed.");
        return "payroll-detail :: content";
    }

    /**
     * Submit a DRAFT run for approval (DRAFT → PENDING_APPROVAL), locking further editing.
     * An empty (uncomputed) or non-DRAFT run raises {@link IllegalStateException} → banner.
     */
    @PostMapping("/htmx/payroll/{id}/submit")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String submit(@PathVariable UUID id, Model model) {
        try {
            payrollRunService.submitForApproval(id);
        } catch (IllegalStateException ex) {
            return renderWithError(id, ex.getMessage(), model);
        }
        model.addAttribute("view", payrollDetailService.build(id));
        model.addAttribute("success", "Payroll submitted for approval.");
        return "payroll-detail :: content";
    }

    /**
     * Approve a PENDING_APPROVAL run (→ APPROVED), recording the current user as approver
     * and firing payslip notifications. FINANCE_OFFICER/SUPER_ADMIN only (segregation of
     * duties). A non-pending run raises {@link IllegalStateException} → banner.
     */
    @PostMapping("/htmx/payroll/{id}/approve")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public String approve(@PathVariable UUID id, Model model) {
        try {
            payrollRunService.approvePayroll(id, currentUserId());
        } catch (IllegalStateException ex) {
            return renderWithError(id, ex.getMessage(), model);
        }
        model.addAttribute("view", payrollDetailService.build(id));
        model.addAttribute("success", "Payroll approved.");
        return "payroll-detail :: content";
    }

    /**
     * Reject a PENDING_APPROVAL run (→ REJECTED) with a reason from the inline form.
     * HR_ADMIN/FINANCE_OFFICER/SUPER_ADMIN — matching the REST
     * {@code POST /api/payroll/{id}/reject} gate (reject is not approval, so the initiator
     * role may send a submission back). A non-pending run raises
     * {@link IllegalStateException} → banner.
     */
    @PostMapping("/htmx/payroll/{id}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public String reject(@PathVariable UUID id, @RequestParam("reason") String reason, Model model) {
        try {
            payrollRunService.rejectPayroll(id, reason);
        } catch (IllegalStateException ex) {
            return renderWithError(id, ex.getMessage(), model);
        }
        model.addAttribute("view", payrollDetailService.build(id));
        model.addAttribute("success", "Payroll rejected.");
        return "payroll-detail :: content";
    }

    /**
     * Re-render the detail {@code content} fragment for {@code id} with an {@code ${error}}
     * banner, returning HTTP 200 so HTMX performs the swap (it skips swaps on 4xx/5xx). Used
     * by the write handlers for wrong-state transitions; unlike the JSON
     * {@code GlobalExceptionHandler}, the user stays on the run and sees what went wrong.
     * The {@link IllegalStateException} path is handled here rather than via an
     * {@code @ExceptionHandler} so it doesn't collide with the {@link IllegalArgumentException}
     * → 404 mapping below (which still serves genuinely-missing runs).
     */
    private String renderWithError(UUID id, String message, Model model) {
        model.addAttribute("view", payrollDetailService.build(id));
        model.addAttribute("error", message);
        return "payroll-detail :: content";
    }

    /** Resolve the authenticated user's id (recorded as approver). Mirrors LeaveListController. */
    private UUID currentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email))
                .getId();
    }

    /**
     * Renders an unknown / cross-tenant run id as a 404 "not found" fragment instead of
     * letting it fall through to the JSON {@code GlobalExceptionHandler}. The run is
     * looked up tenant-scoped (via the {@code @SQLRestriction} on PayrollRun), so a valid
     * UUID belonging to another tenant simply isn't found and the service raises
     * {@link IllegalArgumentException} — for the web view that is a missing page, not a
     * 500. Returns HTTP 404 with the fragment so the browser/HTMX shows a clean message.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(Model model, IllegalArgumentException ex) {
        model.addAttribute("message", ex.getMessage());
        return "payroll-detail :: notFound";
    }
}
