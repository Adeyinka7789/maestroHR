package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.loan.LoanPolicy;
import com.admtechhub.maestrohr.loan.LoanPolicyRequest;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTMX controller for loan policy management. Restricted to SYSTEM_ADMIN (tenant owner),
 * HR_ADMIN, and SUPER_ADMIN — employees cannot view or modify policies.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN', 'SUPER_ADMIN')")
public class LoanPolicyController {

    private final LoanPolicyService loanPolicyService;
    private final FeatureAccessService featureAccessService;

    @GetMapping("/htmx/loan-policies")
    public String listPolicies(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        featureAccessService.require(SubscriptionFeature.LOAN_MANAGEMENT);
        List<LoanPolicy> policies = loanPolicyService.getPoliciesForTenant();
        model.addAttribute("policies", policies);
        return "loan-policies :: content";
    }

    @PostMapping("/htmx/loan-policies/create")
    public String createPolicy(
            @ModelAttribute LoanPolicyRequest req,
            Model model) {
        loanPolicyService.createPolicy(req);
        model.addAttribute("success", "Loan policy created.");
        return listPolicies(null, model);
    }

    @GetMapping("/htmx/loan-policies/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("policy", loanPolicyService.getPolicy(id));
        model.addAttribute("policies", loanPolicyService.getPoliciesForTenant());
        return "loan-policies :: editModal";
    }

    @PostMapping("/htmx/loan-policies/{id}/update")
    public String updatePolicy(
            @PathVariable UUID id,
            @ModelAttribute LoanPolicyRequest req,
            Model model) {
        loanPolicyService.updatePolicy(id, req);
        model.addAttribute("success", "Loan policy updated.");
        return listPolicies(null, model);
    }

    @PostMapping("/htmx/loan-policies/{id}/delete")
    public String deletePolicy(@PathVariable UUID id, Model model) {
        loanPolicyService.deletePolicy(id);
        model.addAttribute("success", "Loan policy deleted.");
        return listPolicies(null, model);
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleError(RuntimeException ex, Model model) {
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("policies", loanPolicyService.getPoliciesForTenant());
        return "loan-policies :: content";
    }

    /** Feature off / not entitled: locked state only — never load policy data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Loan Policies");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }
}
