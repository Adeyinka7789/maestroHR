package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.loan.EmployeeLoan;
import com.admtechhub.maestrohr.loan.LoanEligibilityResponse;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.loan.LoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST endpoints for loan operations that don't fit the HTMX flow —
 * the waiver write-off action and the self-service eligibility check.
 */
@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanApiController {

    private final LoanService loanService;
    private final LoanPolicyService loanPolicyService;
    private final EmployeeRepository employeeRepository;

    /**
     * Returns the authenticated employee's loan eligibility against their effective policy.
     * Used by the My Loans page to populate the eligibility card and constrain the form.
     */
    @GetMapping("/my-eligibility")
    public ResponseEntity<?> myEligibility(Authentication authentication) {
        Optional<Employee> empOpt = employeeRepository.findByEmail(authentication.getName());
        if (empOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No employee profile found for this account."));
        }
        LoanEligibilityResponse resp = loanPolicyService.checkEligibility(empOpt.get().getId());
        return ResponseEntity.ok(resp);
    }

    /**
     * Write off the remaining balance of a loan.
     * Body: {@code {"reason": "..."}}
     */
    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> waiveLoan(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        String reason = body != null ? body.get("reason") : null;
        EmployeeLoan loan = loanService.waiveLoan(id, reason, authentication.getName());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "loanId", loan.getId(),
                "status", loan.getStatus().name(),
                "waivedBy", loan.getWaivedBy()
        ));
    }
}
