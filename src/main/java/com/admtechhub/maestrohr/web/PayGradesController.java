package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

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
 * SCOPE: read-only list + search, keeping the richer card layout (summary strip +
 * relative-pay bars + assigned headcount). Create/edit (the legacy modal) is
 * deferred to a follow-up step; static/pay-grades.html remains on disk as the
 * legacy fallback. This controller takes over {@code /htmx/pay-grades} from
 * {@link PageController}, which previously forwarded to that static page.
 */
@Controller
@RequiredArgsConstructor
public class PayGradesController {

    private final PayGradeListService payGradeListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/pay-grades")
    public String payGrades(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
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
}
