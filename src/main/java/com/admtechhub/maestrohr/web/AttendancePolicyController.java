package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendancePolicy;
import com.admtechhub.maestrohr.attendance.AttendancePolicyRequest;
import com.admtechhub.maestrohr.attendance.AttendancePolicyService;
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
 * HTMX controller for attendance policy management. Restricted to SYSTEM_ADMIN (tenant owner),
 * HR_ADMIN, and SUPER_ADMIN — employees cannot view or modify policies. Mirrors
 * {@link LoanPolicyController} exactly.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN', 'SUPER_ADMIN')")
public class AttendancePolicyController {

    private final AttendancePolicyService attendancePolicyService;
    private final FeatureAccessService featureAccessService;

    @GetMapping("/htmx/attendance-policies")
    public String listPolicies(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        featureAccessService.require(SubscriptionFeature.ATTENDANCE_TRACKING);
        List<AttendancePolicy> policies = attendancePolicyService.getPoliciesForTenant();
        model.addAttribute("policies", policies);
        return "attendance-policies :: content";
    }

    @PostMapping("/htmx/attendance-policies/create")
    public String createPolicy(
            @ModelAttribute AttendancePolicyRequest req,
            Model model) {
        attendancePolicyService.createPolicy(req);
        model.addAttribute("success", "Attendance policy created.");
        return listPolicies(null, model);
    }

    @GetMapping("/htmx/attendance-policies/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("policy", attendancePolicyService.getPolicy(id));
        model.addAttribute("policies", attendancePolicyService.getPoliciesForTenant());
        return "attendance-policies :: editModal";
    }

    @PostMapping("/htmx/attendance-policies/{id}/update")
    public String updatePolicy(
            @PathVariable UUID id,
            @ModelAttribute AttendancePolicyRequest req,
            Model model) {
        attendancePolicyService.updatePolicy(id, req);
        model.addAttribute("success", "Attendance policy updated.");
        return listPolicies(null, model);
    }

    @PostMapping("/htmx/attendance-policies/{id}/delete")
    public String deletePolicy(@PathVariable UUID id, Model model) {
        attendancePolicyService.deletePolicy(id);
        model.addAttribute("success", "Attendance policy deleted.");
        return listPolicies(null, model);
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleError(RuntimeException ex, Model model) {
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("policies", attendancePolicyService.getPoliciesForTenant());
        return "attendance-policies :: content";
    }

    /** Feature off / not entitled: locked state only — never load policy data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Attendance Policies");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }
}
