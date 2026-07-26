package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.gl.GlDtos.CostCenterForm;
import com.admtechhub.maestrohr.gl.GlDtos.GlConfigView;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Cost-center manager + GL account config (server-rendered HTMX fragment). Employees are tagged to
 * cost centers so a payroll run's journal can attribute expenses per branch; the GL account codes
 * here drive that journal ({@link GlExportService}). Gated by {@link SubscriptionFeature#BASIC_PAYROLL}
 * with a manual role check on the page GET, mirroring the other payroll pages.
 */
@Controller
@RequiredArgsConstructor
public class CostCenterController {

    private final CostCenterService costCenterService;
    private final GlExportService glExportService;
    private final FeatureAccessService featureAccessService;

    private static final String[] ROLES = {
            "ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    @GetMapping("/htmx/cost-centers")
    public String costCenters(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();
        populate(model);
        return "cost-centers :: content";
    }

    @PostMapping("/htmx/cost-centers/create")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) String location,
                         @RequestParam(required = false) String glAccountCode,
                         Model model) {
        costCenterService.create(new CostCenterForm(name, code, location, glAccountCode));
        model.addAttribute("success", "Cost center created.");
        populate(model);
        return "cost-centers :: content";
    }

    @PostMapping("/htmx/cost-centers/{id}/update")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String update(@PathVariable UUID id,
                         @RequestParam String name,
                         @RequestParam(required = false) String location,
                         @RequestParam(required = false) String glAccountCode,
                         Model model) {
        costCenterService.update(id, new CostCenterForm(name, null, location, glAccountCode));
        model.addAttribute("success", "Cost center updated.");
        populate(model);
        return "cost-centers :: content";
    }

    @PostMapping("/htmx/cost-centers/{id}/toggle")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String toggle(@PathVariable UUID id, @RequestParam boolean active, Model model) {
        costCenterService.setActive(id, active);
        model.addAttribute("success", active ? "Cost center activated." : "Cost center deactivated.");
        populate(model);
        return "cost-centers :: content";
    }

    /** Bulk-assign the selected employees to a cost center (blank id = unassign), then re-render. */
    @PostMapping("/htmx/cost-centers/assign")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String assign(@RequestParam(required = false) UUID costCenterId,
                         @RequestParam(name = "employeeIds", required = false) List<UUID> employeeIds,
                         Model model) {
        int n = costCenterService.assignToCostCenter(costCenterId, employeeIds);
        model.addAttribute("success", costCenterId != null
                ? n + " employee(s) assigned to the cost center."
                : n + " employee(s) unassigned.");
        populate(model);
        return "cost-centers :: content";
    }

    @PostMapping("/htmx/gl-config")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public String saveConfig(@RequestParam String salaryExpenseAccount,
                             @RequestParam String pensionExpenseAccount,
                             @RequestParam String netPayAccount,
                             @RequestParam String payePayableAccount,
                             @RequestParam String pensionPayableAccount,
                             @RequestParam String nhfPayableAccount,
                             @RequestParam String otherDeductionsAccount,
                             Model model) {
        glExportService.updateConfig(new GlConfigView(salaryExpenseAccount, pensionExpenseAccount,
                netPayAccount, payePayableAccount, pensionPayableAccount, nhfPayableAccount,
                otherDeductionsAccount));
        model.addAttribute("success", "GL account mapping saved.");
        populate(model);
        return "cost-centers :: content";
    }

    // ── failure rendering ──────────────────────────────────────────────────────────

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        populate(model);
        return "cost-centers :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Cost Centers");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void populate(Model model) {
        model.addAttribute("costCenters", costCenterService.list());
        model.addAttribute("config", glExportService.getConfigView());
        model.addAttribute("assignable", costCenterService.listAssignableEmployees());
    }

    private void gate() {
        if (!hasAnyRole()) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
        featureAccessService.require(SubscriptionFeature.BASIC_PAYROLL);
    }

    private boolean hasAnyRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            for (String role : ROLES) {
                if (role.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }
}
