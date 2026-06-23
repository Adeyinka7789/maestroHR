package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final DepartmentDetailService departmentDetailService;
    private final DepartmentService departmentService;

    /** Carries submitted form values back into the template on validation error. */
    record DeptForm(UUID id, String name, String headEmployeeId) {}

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

    /**
     * Department detail page. App shell on a cold visit (layout.js re-requests this
     * route under HTMX); the populated fragment under HTMX. A department id that does
     * not belong to the current tenant reads as not-found (hidden by the entity's
     * {@code @SQLRestriction}) and falls back to the list with an error banner.
     */
    @GetMapping("/htmx/departments/{id}")
    public String departmentDetail(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        DepartmentDetailView detail = departmentDetailService.buildDetail(id);
        if (detail == null) {
            model.addAttribute("error", "Department not found.");
            model.addAttribute("view", departmentListService.buildList(null));
            return "departments :: content";
        }

        model.addAttribute("detail", detail);
        return "department-detail :: content";
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
            @RequestParam(value = "headEmployeeId", defaultValue = "") String headEmployeeId,
            Model model) {

        String trimmedName = name.trim();
        boolean isEdit = !idStr.isBlank();
        UUID id = isEdit ? UUID.fromString(idStr) : null;
        DeptForm form = new DeptForm(id, name, headEmployeeId);

        if (trimmedName.isBlank()) {
            return modalError(model, form, "Department name is required.");
        }

        try {
            if (isEdit) {
                departmentService.update(id, trimmedName, headEmployeeId);
                model.addAttribute("success", "Department updated.");
            } else {
                departmentService.create(trimmedName, headEmployeeId);
                model.addAttribute("success", "Department created.");
            }
        } catch (IllegalArgumentException ex) {
            return modalError(model, form, ex.getMessage());
        }

        model.addAttribute("view", departmentListService.buildList(null));
        return "departments :: content";
    }

    /**
     * Soft-delete (move to trash) a department, then re-render the departments list. The service
     * blocks the delete when live employees are still assigned; that {@link IllegalStateException}
     * (and a not-found {@link IllegalArgumentException}) is surfaced as an error banner on the list
     * rather than leaving the user on a broken detail page. The button lives on the detail page and
     * targets {@code #page-content}, so a successful delete bounces back to the list.
     */
    @PostMapping("/htmx/departments/{id}/delete")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String delete(@PathVariable("id") UUID id, Model model) {
        try {
            departmentService.delete(id);
            model.addAttribute("success", "Department moved to trash.");
        } catch (IllegalStateException | IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        }
        model.addAttribute("view", departmentListService.buildList(null));
        return "departments :: content";
    }

    /**
     * Re-renders the list with the modal reopened (server-side) and an in-modal banner.
     * On an <em>edit</em> error the department's roster is loaded so the HOD dropdown
     * renders with the in-progress selection preserved — the client-side data-employees
     * path only runs on a fresh Edit click, not on this server re-render. On a
     * <em>create</em> error there is no department yet, so the dropdown shows only "None".
     */
    private String modalError(Model model, DeptForm form, String message) {
        model.addAttribute("formValues", form);
        model.addAttribute("modalError", message);
        if (form.id() != null) {
            model.addAttribute("formEmployees", departmentListService.employeesForDepartment(form.id()));
        }
        model.addAttribute("view", departmentListService.buildList(null));
        return "departments :: content";
    }
}
