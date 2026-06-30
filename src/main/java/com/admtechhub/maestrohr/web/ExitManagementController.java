package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.exit.ExitRequestDTO;
import com.admtechhub.maestrohr.exit.ExitService;
import com.admtechhub.maestrohr.exit.FinalSettlementDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * HTMX controller for the exit-management page (Option 3 server-rendered fragment).
 * Mirrors {@link LoanListController}. Restricted to SYSTEM_ADMIN / HR_ADMIN / SUPER_ADMIN.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN', 'SUPER_ADMIN')")
public class ExitManagementController {

    private final ExitService exitService;
    private final ExitManagementService exitManagementService;

    @GetMapping("/htmx/exit-management")
    public String list(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) return "forward:/layout.html";
        model.addAttribute("view", exitManagementService.buildList());
        return "exit-management :: list";
    }

    @GetMapping("/htmx/exit-management/{id}")
    public String detail(
            @PathVariable UUID id,
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) return "forward:/layout.html";
        ExitRequestDTO dto = exitService.getExitRequestById(id);
        model.addAttribute("exit", dto);
        // Pre-populate settlement form with auto-calculated values when clearance is complete
        if ("CLEARANCE_COMPLETE".equals(dto.getStatus()) && dto.getSettlement() == null) {
            try {
                model.addAttribute("autoSettlement", exitService.calculateAutoSettlement(id));
            } catch (Exception ex) {
                model.addAttribute("settlementError", ex.getMessage());
            }
        }
        return "exit-management :: detail";
    }

    @PostMapping("/htmx/exit-management/create")
    public String create(
            @RequestParam UUID employeeId,
            @RequestParam String exitType,
            @RequestParam String lastWorkingDay,
            @RequestParam(required = false) String reason,
            Model model) {
        exitService.createExitRequest(employeeId, exitType,
                LocalDate.parse(lastWorkingDay), reason);
        model.addAttribute("success", "Exit request created and clearance process started.");
        model.addAttribute("view", exitManagementService.buildList());
        return "exit-management :: list";
    }

    @PostMapping("/htmx/exit-management/{id}/clearance/{itemId}/clear")
    public String clearItem(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            Authentication authentication,
            Model model) {
        String clearedBy = authentication != null ? authentication.getName() : "system";
        exitService.updateClearanceItem(id, itemId, "CLEARED", clearedBy);
        ExitRequestDTO dto = exitService.getExitRequestById(id);
        model.addAttribute("exit", dto);
        if ("CLEARANCE_COMPLETE".equals(dto.getStatus()) && dto.getSettlement() == null) {
            try {
                model.addAttribute("autoSettlement", exitService.calculateAutoSettlement(id));
            } catch (Exception ex) {
                model.addAttribute("settlementError", ex.getMessage());
            }
        }
        model.addAttribute("success", "Item cleared.");
        return "exit-management :: detail";
    }

    @PostMapping("/htmx/exit-management/{id}/settle")
    public String settle(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") BigDecimal unpaidSalary,
            @RequestParam(defaultValue = "0") BigDecimal accruedLeave,
            @RequestParam(defaultValue = "0") BigDecimal severancePay,
            @RequestParam(defaultValue = "0") BigDecimal loanDeduction,
            @RequestParam(defaultValue = "0") BigDecimal otherDeductions,
            Model model) {
        exitService.calculateSettlement(id, unpaidSalary, accruedLeave,
                severancePay, loanDeduction, otherDeductions);
        model.addAttribute("success", "Settlement saved. Employee terminated.");
        model.addAttribute("exit", exitService.getExitRequestById(id));
        return "exit-management :: detail";
    }

    @PostMapping("/htmx/exit-management/{id}/mark-paid")
    public String markPaid(@PathVariable UUID id, Model model) {
        exitService.markPaid(id);
        model.addAttribute("success", "Payment confirmed. Exit process completed.");
        model.addAttribute("exit", exitService.getExitRequestById(id));
        return "exit-management :: detail";
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleError(RuntimeException ex, Model model) {
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("view", exitManagementService.buildList());
        return "exit-management :: list";
    }
}
