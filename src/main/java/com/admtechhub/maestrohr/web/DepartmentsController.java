package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.DepartmentService;
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
 * Departments list route (Option 3 — server-rendered fragment), mirroring the
 * employees page. The HTMX request renders templates/departments.html with the
 * table already in the markup (no /api/departments double-fetch, no loading flash);
 * a non-HTMX visit returns the static layout shell and layout.js re-requests this
 * route with the HX-Request header.
 *
 * Departments are few per tenant, so the only filter is a name search driven by
 * {@code /htmx/departments/table}, which swaps just the table fragment. There is
 * no pagination and no status/department filter.
 *
 * Write actions (create / edit) post to {@code /htmx/departments/save}, distinguished
 * by the presence of an {@code id} parameter. On success the whole {@code content}
 * fragment is re-rendered (so the department count in the header recalculates) with
 * a success banner. On validation failure or duplicate name the modal stays open
 * with a {@code modalError} banner inside it.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class DepartmentsController {

    private final DepartmentListService departmentListService;
    private final DepartmentService departmentService;

    /** Carries submitted form values back into the template on validation error. */
    record DeptForm(UUID id, String name) {}

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/departments")
    public String departments(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        model.addAttribute("view", departmentListService.buildList(q));
        return "departments :: content";
    }

    /** Table body only — the swap target for the search box. */
    @GetMapping("/htmx/departments/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        model.addAttribute("view", departmentListService.buildList(q));
        return "departments :: table";
    }

    /**
     * Create (no {@code id} param) or update (with {@code id}) a department.
     * On success re-renders {@code content} so the department count in the header
     * recalculates. On error re-renders {@code content} with the modal open and
     * an in-modal banner.
     */
    @PostMapping("/htmx/departments/save")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String save(
            @RequestParam(value = "id", defaultValue = "") String idStr,
            @RequestParam(value = "name", defaultValue = "") String name,
            Model model) {

        String trimmedName = name.trim();
        boolean isEdit = !idStr.isBlank();
        UUID id = isEdit ? UUID.fromString(idStr) : null;
        DeptForm form = new DeptForm(id, name);

        if (trimmedName.isBlank()) {
            return modalError(model, form, "Department name is required.");
        }

        try {
            if (isEdit) {
                departmentService.update(id, trimmedName);
                model.addAttribute("success", "Department updated.");
            } else {
                departmentService.create(trimmedName);
                model.addAttribute("success", "Department created.");
            }
        } catch (IllegalArgumentException ex) {
            return modalError(model, form, ex.getMessage());
        }

        model.addAttribute("view", departmentListService.buildList(null));
        return "departments :: content";
    }

    private String modalError(Model model, DeptForm form, String message) {
        model.addAttribute("formValues", form);
        model.addAttribute("modalError", message);
        model.addAttribute("view", departmentListService.buildList(null));
        return "departments :: content";
    }
}
