package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
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

import java.util.UUID;

/**
 * Compliance &amp; Expiry dashboard (server-rendered HTMX fragment, Option 3), consolidating the
 * two document/probation compliance concerns HR would otherwise chase through logs and individual
 * profiles: probation reviews falling due and employee documents (contracts, work permits, IDs)
 * nearing expiry. A cold visit returns the app shell; layout.js re-requests with the HX-Request
 * header and this route renders {@code compliance :: content} into {@code #page-content}.
 *
 * <p>Gated to HR/super-admin (SYSTEM_ADMIN inherits HR_ADMIN via the role hierarchy). The confirm
 * action here is the same one-click probation confirmation offered on the employee detail page,
 * re-rendering the dashboard so the row drops off immediately.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class ComplianceController {

    private final ComplianceDashboardService complianceDashboardService;
    private final EmployeeService employeeService;
    private final FeatureAccessService featureAccessService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/compliance")
    public String compliance(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        featureAccessService.require(SubscriptionFeature.COMPLIANCE);
        model.addAttribute("view", complianceDashboardService.build());
        return "compliance :: content";
    }

    /**
     * One-click probation confirmation from the dashboard, then re-render so the confirmed
     * employee's row disappears. Mirrors the REST/detail confirm; idempotent service-side.
     */
    @PostMapping("/htmx/compliance/confirm/{id}")
    public String confirm(@PathVariable UUID id, Model model) {
        employeeService.confirmEmployee(id);
        model.addAttribute("success", "Employee confirmed.");
        model.addAttribute("view", complianceDashboardService.build());
        return "compliance :: content";
    }

    /** Any action/read failure re-renders the dashboard with an error banner instead of a 500. */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("view", complianceDashboardService.build());
        return "compliance :: content";
    }

    /** Feature off / not entitled: locked state only — never load compliance data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Compliance");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }
}
