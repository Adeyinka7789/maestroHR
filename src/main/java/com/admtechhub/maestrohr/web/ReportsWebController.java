package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.LocalDate;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Server-rendered Reports page (Option 3 pattern). Mirrors PayrollListController.
 * Owns /htmx/reports (replaces the forwarding stub in PageController).
 *
 * All downloads are native form-GETs targeting existing /api/reports/* endpoints —
 * no JavaScript required. Employee and payroll-run dropdowns are server-populated.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
public class ReportsWebController {

    private final EmployeeRepository employeeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final FeatureAccessService featureAccessService;

    /** Full page: app shell on cold visit, populated fragment under HTMX. */
    @GetMapping("/htmx/reports")
    public String reports(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        featureAccessService.require(SubscriptionFeature.CUSTOM_REPORTING);
        model.addAttribute("view", buildView());
        return "reports :: content";
    }

    /** Feature off / not entitled: locked state only — never load report data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Reports");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    private ReportsListView buildView() {
        UUID tenantId = currentTenantId();

        List<ReportsListView.EmployeeOption> employees = employeeRepository
                .findByStatus(EmployeeStatus.ACTIVE)
                .stream()
                .sorted(Comparator.comparing(Employee::getFullName))
                .map(e -> new ReportsListView.EmployeeOption(
                        e.getId(),
                        e.getFullName() + " (" + e.getEmployeeNumber() + ")"))
                .toList();

        List<ReportsListView.PayrollRunOption> payrollRuns = payrollRunRepository
                .findAllByTenant_IdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(r -> new ReportsListView.PayrollRunOption(
                        r.getId(),
                        monthName(r.getPayrollMonth()) + " " + r.getPayrollYear()))
                .toList();

        LocalDate today = LocalDate.now();
        return new ReportsListView(
                employees,
                payrollRuns,
                today.getMonthValue(),
                today.getYear(),
                today.getYear() + "-01-01",
                today.getYear() + "-12-31"
        );
    }

    private UUID currentTenantId() {
        String id = TenantContext.getCurrentTenant();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("No tenant context");
        }
        return UUID.fromString(id);
    }

    private static String monthName(int month) {
        return Month.of(month).getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
    }
}
