package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.leave.LeaveStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Leave list route (Option 3 — server-rendered fragment), mirroring the
 * departments / pay-grades pages. The HTMX request renders templates/leave.html
 * with the approval-queue / history table already in the markup (no /api/leave
 * double-fetch, no loading flash); a non-HTMX visit returns the static layout shell
 * and layout.js re-requests this route with the HX-Request header.
 *
 * This is the HR-admin / manager-facing view. A status filter (All / Pending /
 * Approved / Rejected) plus a free-text search are driven by
 * {@code /htmx/leave/table}, which swaps the chip strip + table fragment.
 *
 * SCOPE (Step A): read-only list + status filter + search. The approve/reject write
 * actions and the admin "apply for leave" form are deferred to later steps;
 * static/leave.html remains on disk as the legacy fallback until this fragment is
 * browser-verified.
 *
 * NOTE: named {@code LeaveListController} (not {@code LeaveController}) on purpose —
 * the REST API controller {@link com.admtechhub.maestrohr.leave.LeaveController}
 * already owns the default {@code leaveController} bean name; a second bean with that
 * name would fail context startup.
 */
@Controller
@RequiredArgsConstructor
public class LeaveListController {

    private final LeaveListService leaveListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/leave")
    public String leave(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        return "leave :: content";
    }

    /** Chip strip + table only — the swap target for search and the status chips. */
    @GetMapping("/htmx/leave/table")
    public String table(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        model.addAttribute("view", leaveListService.buildList(q, parseStatus(status)));
        return "leave :: table";
    }

    /**
     * Maps the status query param to a {@link LeaveStatus} filter:
     *   - absent (null)  → default to PENDING (the approval queue is the landing view);
     *   - blank ("")     → null = the "All" chip;
     *   - valid name     → that status;
     *   - unknown value  → null = All (show everything rather than silently hide data).
     */
    private LeaveStatus parseStatus(String value) {
        if (value == null) {
            return LeaveStatus.PENDING;
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return LeaveStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
