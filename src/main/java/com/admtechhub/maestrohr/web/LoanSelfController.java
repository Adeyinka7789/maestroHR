package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.subscription.FeatureNotAvailableException;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Employee self-service "My Loans" route (Option 3 — server-rendered fragment), the
 * read-and-apply companion to {@link PayslipSelfController}. The EMPLOYEE-facing surface:
 * the employee sees their own loan requests / loans and applies for a new one. HR / Finance
 * approve and track them on {@link LoanListController}.
 *
 * <p>Visible to any authenticated user; the employee is always resolved from the session,
 * never a request param, so a user can only ever apply for and view their own loans (the IDOR
 * guard). Accounts with no Employee record get a friendly no-profile card.
 */
@Controller
@RequiredArgsConstructor
public class LoanSelfController {

    private static final String NO_PROFILE_MESSAGE =
            "This page is for employees. Your account doesn't have an associated employee profile.";

    private final LoanSelfService loanSelfService;
    private final LoanService loanService;
    private final EmployeeService employeeService;

    /** Full page: app shell on a cold visit, the populated list + apply form under HTMX. */
    @GetMapping("/htmx/loans/me")
    public String myLoans(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        return renderForCurrentEmployee(model);
    }

    /**
     * Apply for a loan as the authenticated employee. The employee is resolved from the session
     * (the IDOR guard — no employee id is accepted from the request). Creates a PENDING request
     * for HR/Finance to approve. Validation failures render an in-place {@code formError} banner.
     */
    @PostMapping("/htmx/loans/me/apply")
    @RequiresFeature(SubscriptionFeature.LOAN_MANAGEMENT)
    public String apply(
            @RequestParam("amount") String amount,
            @RequestParam("repaymentMonths") String repaymentMonths,
            @RequestParam("startDate") String startDate,
            @RequestParam(value = "description", required = false) String description,
            Model model) {

        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "loans-me :: content";
        }

        try {
            long amountKobo = parseAmountToKobo(amount);
            int months = parseMonths(repaymentMonths);
            LocalDate start = parseRequiredDate(startDate, "Start date");
            loanService.applyForLoan(employee.getId(), amountKobo, months, start, description);
            model.addAttribute("success", "Loan request submitted. HR will review it shortly.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("formError", ex.getMessage());
        }

        model.addAttribute("view", loanSelfService.build(employee.getId(), employee.getFullName()));
        return "loans-me :: content";
    }

    /** Re-render the fragment for the authenticated employee, or the no-profile card. */
    private String renderForCurrentEmployee(Model model) {
        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "loans-me :: content";
        }
        model.addAttribute("view", loanSelfService.build(employee.getId(), employee.getFullName()));
        return "loans-me :: content";
    }

    /**
     * Feature-gate failures (plan/flag) and unexpected state errors are rendered in place
     * (HTTP 200 so HTMX still swaps) rather than reaching the JSON handler. Rebuilt for the
     * current employee so the page stays intact behind the banner.
     */
    @ExceptionHandler({FeatureNotAvailableException.class, IllegalStateException.class})
    public String handleActionFailure(RuntimeException ex, Model model) {
        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "loans-me :: content";
        }
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("view", loanSelfService.build(employee.getId(), employee.getFullName()));
        return "loans-me :: content";
    }

    private EmployeeDetailsDTO currentEmployeeOrNull() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return employeeService.findByEmail(email);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── parsing helpers ───────────────────────────────────────────────────────

    private long parseAmountToKobo(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("Loan amount is required.");
        }
        try {
            BigDecimal naira = new BigDecimal(amount.trim().replace(",", ""));
            if (naira.signum() <= 0) {
                throw new IllegalArgumentException("Loan amount must be greater than zero.");
            }
            return naira.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("Loan amount is not a valid number.");
        }
    }

    private int parseMonths(String months) {
        if (months == null || months.isBlank()) {
            throw new IllegalArgumentException("Repayment months is required.");
        }
        try {
            int m = Integer.parseInt(months.trim());
            if (m <= 0) {
                throw new IllegalArgumentException("Repayment months must be greater than zero.");
            }
            return m;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Repayment months is not a valid whole number.");
        }
    }

    private LocalDate parseRequiredDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(label + " is not a valid date.");
        }
    }
}
