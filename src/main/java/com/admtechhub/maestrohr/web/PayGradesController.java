package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.PayGradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Pay-grades list route (Option 3 — server-rendered fragment), mirroring the
 * departments page. The HTMX request renders templates/pay-grades.html with the
 * card grid already in the markup (no /api/pay-grades double-fetch, no loading
 * flash); a non-HTMX visit returns the static layout shell and layout.js
 * re-requests this route with the HX-Request header.
 *
 * Pay grades are few per tenant, so the only filter is a name search driven by
 * {@code /htmx/pay-grades/table}, which swaps just the card-grid fragment. There is
 * no pagination.
 *
 * Write actions (create / edit) post to {@code /htmx/pay-grades/save}, distinguished
 * by the presence of an {@code id} parameter. On success the whole {@code content}
 * fragment is re-rendered (so the summary strip recalculates) with a success banner.
 * On validation failure or duplicate name the fragment is re-rendered with the modal
 * open, fields pre-populated, and a {@code modalError} banner inside the modal.
 */
@Controller
@RequiredArgsConstructor
public class PayGradesController {

    private final PayGradeListService payGradeListService;
    private final PayGradeService payGradeService;

    /** Carries submitted form values back into the template on validation error. */
    record PayGradeForm(UUID id, String name, String basicSalary,
                        String housingAllowance, String transportAllowance,
                        String otherAllowances) {}

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/pay-grades")
    public String payGrades(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        model.addAttribute("view", payGradeListService.buildList(q));
        return "pay-grades :: content";
    }

    /** Card grid only — the swap target for the search box. */
    @GetMapping("/htmx/pay-grades/table")
    public String grid(
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        model.addAttribute("view", payGradeListService.buildList(q));
        return "pay-grades :: grid";
    }

    /**
     * Create (no {@code id} param) or update (with {@code id}) a pay grade.
     * Salary inputs arrive in whole Naira; stored as kobo (× 100).
     * On success re-renders {@code content} so the summary strip recalculates.
     * On error re-renders {@code content} with the modal open and an in-modal banner.
     */
    @PostMapping("/htmx/pay-grades/save")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String save(
            @RequestParam(value = "id", defaultValue = "") String idStr,
            @RequestParam(value = "name", defaultValue = "") String name,
            @RequestParam(value = "basicSalary", defaultValue = "0") String basicStr,
            @RequestParam(value = "housingAllowance", defaultValue = "0") String housingStr,
            @RequestParam(value = "transportAllowance", defaultValue = "0") String transportStr,
            @RequestParam(value = "otherAllowances", defaultValue = "0") String otherStr,
            Model model) {

        String trimmedName = name.trim();
        boolean isEdit = !idStr.isBlank();
        UUID id = isEdit ? UUID.fromString(idStr) : null;
        PayGradeForm form = new PayGradeForm(id, name, basicStr, housingStr, transportStr, otherStr);

        if (trimmedName.isBlank()) {
            return modalError(model, form, "Grade name is required.");
        }

        long basic, housing, transport, other;
        try {
            basic     = Math.round(Double.parseDouble(basicStr.isBlank()     ? "0" : basicStr)     * 100);
            housing   = Math.round(Double.parseDouble(housingStr.isBlank()   ? "0" : housingStr)   * 100);
            transport = Math.round(Double.parseDouble(transportStr.isBlank() ? "0" : transportStr) * 100);
            other     = Math.round(Double.parseDouble(otherStr.isBlank()     ? "0" : otherStr)     * 100);
        } catch (NumberFormatException ex) {
            return modalError(model, form, "Invalid amount — please enter numbers only.");
        }

        if (basic <= 0) {
            return modalError(model, form, "Basic salary must be greater than zero.");
        }

        try {
            if (isEdit) {
                payGradeService.update(id, trimmedName, basic, housing, transport, other);
                model.addAttribute("success", "Pay grade updated.");
            } else {
                payGradeService.create(trimmedName, basic, housing, transport, other);
                model.addAttribute("success", "Pay grade created.");
            }
        } catch (IllegalArgumentException ex) {
            return modalError(model, form, ex.getMessage());
        }

        model.addAttribute("view", payGradeListService.buildList(null));
        return "pay-grades :: content";
    }

    private String modalError(Model model, PayGradeForm form, String message) {
        model.addAttribute("formValues", form);
        model.addAttribute("modalError", message);
        model.addAttribute("view", payGradeListService.buildList(null));
        return "pay-grades :: content";
    }
}
