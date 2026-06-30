package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.loan.EmployeeLoan;
import com.admtechhub.maestrohr.loan.LoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for loan operations that don't fit the HTMX flow —
 * currently just the waiver write-off action.
 */
@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanApiController {

    private final LoanService loanService;

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
