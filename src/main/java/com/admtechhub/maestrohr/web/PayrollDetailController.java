package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Payroll-run detail route (Option 3 — server-rendered fragment). The HTMX request
 * renders templates/payroll-detail.html with the statutory summary + per-employee
 * breakdown already in the markup (no /api/payroll/{id} double-fetch, no loading flash);
 * a non-HTMX visit returns the static layout shell and layout.js re-requests this route
 * with the HX-Request header.
 *
 * SCOPE (Step B): read-only detail. The submit / approve / reject / disburse writes are
 * deferred to later, separately-reviewed steps because they move money; the view model's
 * action flags are surfaced so the template can pre-render those buttons in the right
 * enabled/hidden state.
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
