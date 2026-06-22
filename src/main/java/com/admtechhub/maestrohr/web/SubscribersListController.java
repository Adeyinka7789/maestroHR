package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Super-admin subscribers list route (Option 3 — server-rendered HTMX fragment), mirroring the
 * {@link EmployeesController} pattern. Replaces the former static-forward in {@code PageController},
 * which served {@code static/subscribers.html} (a JS-rendered partial whose dynamically injected
 * "View" links never reliably bound their {@code hx-*} attributes via {@code htmx.process}).
 *
 * <p>The table now carries server-rendered {@code th:each} rows, so the View link is a plain
 * Thymeleaf-resolved anchor that HTMX wires up at parse time — no JS injection, no skeleton trap.
 *
 * <p>URL-level security ({@code /htmx/subscribers(/**)} → SUPER_ADMIN in SecurityConfig) is the
 * primary gate; {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SubscribersListController {

    private final SubscribersListService subscribersListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/subscribers")
    public String subscribers(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "plan", required = false) String plan,
            Model model) {

        if (htmx == null) {
            // Full-page navigation / refresh: return the app shell; layout.js then re-requests
            // this route with the HX-Request header to fetch the fragment.
            return "forward:/layout.html";
        }

        model.addAttribute("view", subscribersListService.build(q, status, plan));
        return "subscribers :: content";
    }

    /** Table body only — the swap target for search / status / plan filtering. */
    @GetMapping("/htmx/subscribers/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "plan", required = false) String plan,
            Model model) {

        model.addAttribute("view", subscribersListService.build(q, status, plan));
        return "subscribers :: table";
    }
}
