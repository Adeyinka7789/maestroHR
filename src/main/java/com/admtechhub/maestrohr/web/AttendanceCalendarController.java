package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Attendance Monthly Calendar route (Step B, Option 3 — server-rendered fragment),
 * companion to {@link AttendanceListController}. The attendance page surfaces a
 * Today / Calendar tab toggle (rendered by the {@code attendance :: tabs} fragment);
 * the Calendar tab swaps {@code attendance :: calendar} into {@code #page-content}.
 *
 * A single GET drives every interaction — the tab toggle, the employee picker, and the
 * prev/next month navigation — each re-rendering the whole calendar fragment (picker +
 * month nav + grid + summary) with the requested employee and month. A non-HTMX visit
 * returns the static layout shell and layout.js re-requests this route with HX-Request.
 *
 * SCOPE (Step B): read-only. The write workflows — Mark Attendance and Self check-in/out
 * (Steps C/D) — are deferred to later, separately-reviewed steps because the write paths
 * feed payroll deductions.
 *
 * Named {@code AttendanceCalendarController} (not on the REST {@code attendanceController}
 * bean name) for the same reason as {@link AttendanceListController}.
 */
@Controller
@RequiredArgsConstructor
public class AttendanceCalendarController {

    private final AttendanceCalendarService attendanceCalendarService;

    /** Full calendar fragment: app shell on a cold visit, the populated grid under HTMX. */
    @GetMapping("/htmx/attendance/calendar")
    public String calendar(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "empId", required = false) String empId,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view",
                attendanceCalendarService.build(parseEmployeeId(empId), year, month));
        return "attendance :: calendar";
    }

    /**
     * Parses the employee id from the picker. Absent, blank, or unparseable values fall
     * back to null — the service then renders the "select an employee" prompt rather than
     * a 400, so a malformed param degrades gracefully (mirrors the Step-A date parsing).
     */
    private UUID parseEmployeeId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
