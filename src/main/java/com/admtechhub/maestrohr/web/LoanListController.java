package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureNotAvailableException;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * HR / Finance loan-management route (Option 3 — server-rendered fragment). Employees apply
 * for loans from their own account ({@link LoanSelfController}); this page is the
 * approve / track surface — HR or Finance approves or rejects PENDING requests and manages
 * (pause / resume / cancel) the ACTIVE ones. Mirrors {@link LeaveListController}.
 *
 * <p>Gated to {@code HR_ADMIN / FINANCE_OFFICER / SUPER_ADMIN}. Each write also carries
 * {@code @RequiresFeature(LOAN_MANAGEMENT)} so a plan without the loan feature (or a globally
 * disabled flag) cannot act on loans — the read view stays visible so users see the upgrade
 * prompt rather than a blank page, matching the leave page's convention.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
public class LoanListController {

    private final LoanService loanService;
    private final LoanListService loanListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/loans")
    public String loans(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    /** Approve a PENDING request → ACTIVE (starts deducting next payroll run). */
    @PostMapping("/htmx/loans/{id}/approve")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String approve(@PathVariable UUID id, Model model) {
        loanService.approveLoan(id);
        model.addAttribute("success", "Loan approved.");
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    /** Reject a PENDING request → REJECTED with a reason from the inline form. */
    @PostMapping("/htmx/loans/{id}/reject")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String reject(@PathVariable UUID id, @RequestParam("reason") String reason, Model model) {
        loanService.rejectLoan(id, reason);
        model.addAttribute("success", "Loan request rejected.");
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    @PostMapping("/htmx/loans/{id}/pause")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String pause(@PathVariable UUID id, Model model) {
        loanService.pauseLoan(id);
        model.addAttribute("success", "Loan paused.");
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    @PostMapping("/htmx/loans/{id}/resume")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String resume(@PathVariable UUID id, Model model) {
        loanService.resumeLoan(id);
        model.addAttribute("success", "Loan resumed.");
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    @PostMapping("/htmx/loans/{id}/cancel")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String cancel(@PathVariable UUID id, Model model) {
        loanService.cancelLoan(id);
        model.addAttribute("success", "Loan cancelled.");
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }

    /**
     * Renders write-action failures as an in-place fragment (HTTP 200 so HTMX still swaps): a
     * plan/flag gate ({@link FeatureNotAvailableException}) or a state guard
     * ({@link IllegalStateException} — e.g. approving a non-pending request / the double-click
     * race) is shown as a banner on the rebuilt content rather than reaching the JSON handler.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class,
            FeatureAccessException.class})
    public String handleActionFailure(RuntimeException ex, Model model) {
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("view", loanListService.build());
        return "loans :: content";
    }
}
