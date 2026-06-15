package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Attendance list route (Option 3 — server-rendered fragment), mirroring the
 * departments / pay-grades / leave pages. The HTMX request renders
 * templates/attendance.html with the daily roster already in the markup (no
 * /api/attendance double-fetch, no loading flash); a non-HTMX visit returns the
 * static layout shell and layout.js re-requests this route with the HX-Request header.
 *
 * This is the HR-admin / manager-facing daily roster. A date picker (defaulting to
 * today), a status filter (All / Present / Late / Absent / Half Day), and a free-text
 * employee search are driven by {@code /htmx/attendance/table}, which swaps the day
 * heading + chip strip + roster fragment.
 *
 * SCOPE (Step A): read-only single-day list + date picker + status filter + search.
 * The Monthly Calendar (Step B) and the write workflows — Mark Attendance and Self
 * check-in/out (Steps C/D) — are deferred to later, separately-reviewed steps because
 * the write paths feed payroll deductions; static/attendance.html remains on disk as
 * the legacy fallback until this fragment is browser-verified.
 *
 * NOTE: named {@code AttendanceListController} (not {@code AttendanceController}) on
 * purpose — the REST API controller
 * {@link com.admtechhub.maestrohr.attendance.AttendanceController} already owns the
 * default {@code attendanceController} bean name; a second bean with that name would
 * fail context startup.
 */
@Controller
@RequiredArgsConstructor
public class AttendanceListController {

    private final AttendanceListService attendanceListService;

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/attendance")
    public String attendance(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view",
                attendanceListService.buildList(parseDate(date), q, parseStatus(status)));
        return "attendance :: content";
    }

    /** Day heading + chip strip + roster only — the swap target for the date picker, search, and chips. */
    @GetMapping("/htmx/attendance/table")
    public String table(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            Model model) {

        model.addAttribute("view",
                attendanceListService.buildList(parseDate(date), q, parseStatus(status)));
        return "attendance :: table";
    }

    /**
     * Parses the selected day from the date-picker param. Absent, blank, or unparseable
     * values fall back to today (the service also defaults null to today) so a malformed
     * param shows today's roster rather than a 400.
     */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return LocalDate.now();
        }
    }

    /**
     * Maps the status query param to an {@link AttendanceStatus} filter:
     *   - absent (null) / blank ("") → null = the "All" chip (the daily roster landing view);
     *   - valid name                 → that status;
     *   - unknown value              → null = All (show everything rather than silently hide data).
     */
    private AttendanceStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AttendanceStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
