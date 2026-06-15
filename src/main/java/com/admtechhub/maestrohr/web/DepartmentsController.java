package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

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
 * SCOPE: read-only list + search. Create/edit (the legacy modal) is deferred to a
 * follow-up step; static/departments.html remains on disk as the legacy fallback.
 */
@Controller
@RequiredArgsConstructor
public class DepartmentsController {

    private final DepartmentListService departmentListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/departments")
    public String departments(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
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
}
