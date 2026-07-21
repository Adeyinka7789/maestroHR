package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.Shift;
import com.admtechhub.maestrohr.attendance.ShiftRequest;
import com.admtechhub.maestrohr.attendance.ShiftService;
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
 * HTMX controller for shift management. Restricted to SYSTEM_ADMIN (tenant owner), HR_ADMIN,
 * and SUPER_ADMIN — same gate as {@link AttendancePolicyController} / {@link LoanPolicyController}.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN', 'SUPER_ADMIN')")
public class ShiftController {

    private final ShiftService shiftService;
    private final FeatureAccessService featureAccessService;

    @GetMapping("/htmx/shifts")
    public String listShifts(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        featureAccessService.require(SubscriptionFeature.ATTENDANCE_TRACKING);
        List<Shift> shifts = shiftService.getShiftsForTenant();
        model.addAttribute("shifts", shifts);
        return "shifts :: content";
    }

    @PostMapping("/htmx/shifts/create")
    public String createShift(
            @ModelAttribute ShiftRequest req,
            Model model) {
        shiftService.createShift(req);
        model.addAttribute("success", "Shift created.");
        return listShifts(null, model);
    }

    @GetMapping("/htmx/shifts/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("shift", shiftService.getShift(id));
        model.addAttribute("shifts", shiftService.getShiftsForTenant());
        return "shifts :: editModal";
    }

    @PostMapping("/htmx/shifts/{id}/update")
    public String updateShift(
            @PathVariable UUID id,
            @ModelAttribute ShiftRequest req,
            Model model) {
        shiftService.updateShift(id, req);
        model.addAttribute("success", "Shift updated.");
        return listShifts(null, model);
    }

    @PostMapping("/htmx/shifts/{id}/delete")
    public String deleteShift(@PathVariable UUID id, Model model) {
        shiftService.deleteShift(id);
        model.addAttribute("success", "Shift deleted.");
        return listShifts(null, model);
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleError(RuntimeException ex, Model model) {
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("shifts", shiftService.getShiftsForTenant());
        return "shifts :: content";
    }

    /** Feature off / not entitled: locked state only — never load shift data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Shifts");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }
}
