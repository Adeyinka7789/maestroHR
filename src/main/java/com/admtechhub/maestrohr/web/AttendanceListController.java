package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecordDTO;
import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

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
 * SCOPE: read-only single-day list + date picker + status filter + search (Step A),
 * plus the HR/manager Mark Attendance write (Step C) — a top "Mark Attendance" form and
 * a per-row Edit form, both posting to {@code /htmx/attendance/mark} and re-rendering the
 * whole {@code content} fragment (so the date picker, chips, and roster all reflect the
 * marked day). This write feeds payroll deductions, so an ABSENT mark is gated by a
 * client-side confirm (see the {@code data-confirm-absent} hook in layout.js) and the
 * service-side guards in {@link AttendanceService#markAttendance}.
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
    private final AttendanceService attendanceService;

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
     * HR/manager Mark Attendance write (Step C). Upserts the record for {@code employeeId}
     * on {@code date} via {@link AttendanceService#markAttendance} (the payroll-impacting
     * path), then re-renders the whole {@code content} fragment for the MARKED day so the
     * date picker, chips, and roster all reflect it and the just-saved row is visible.
     *
     * The status filter is intentionally reset to All here (only {@code q} is carried) so a
     * freshly-marked row is never hidden by the chip the user happened to be on — and to
     * avoid a name clash between the form's {@code status} field (the attendance status) and
     * the roster's {@code status} filter carrier. A success banner confirms the save
     * regardless of which rows the search term leaves visible.
     */
    @PostMapping("/htmx/attendance/mark")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String mark(
            @RequestParam("employeeId") UUID employeeId,
            @RequestParam("date") String date,
            @RequestParam("status") AttendanceStatus status,
            @RequestParam(value = "clockInTime", required = false) String clockInTime,
            @RequestParam(value = "clockOutTime", required = false) String clockOutTime,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "q", required = false) String q,
            Model model) {

        LocalDate markDate = parseDate(date);
        AttendanceRecordDTO saved =
                attendanceService.markAttendance(employeeId, markDate, status, clockInTime, clockOutTime, notes);

        model.addAttribute("view", attendanceListService.buildList(markDate, q, null));
        model.addAttribute("success",
                "Attendance saved for " + saved.getEmployeeName() + " on " + markDate + " (" + status.name() + ").");
        return "attendance :: content";
    }

    /**
     * Renders Mark Attendance failures as the in-place {@code content} fragment with an
     * {@code ${error}} banner instead of letting them fall through to the JSON
     * {@code GlobalExceptionHandler}. Covers the Step C guards — future date / reversed
     * clock times ({@link IllegalStateException}) and bad input such as a malformed time or
     * unknown employee ({@link IllegalArgumentException}). Returns HTTP 200 with the rebuilt
     * fragment (for the attempted day, recovered from the failed request's params) so HTMX
     * still performs the swap; the banner explains what went wrong. Mirrors
     * {@link LeaveListController#handleActionFailure}.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleMarkFailure(RuntimeException ex, HttpServletRequest request, Model model) {
        String date = request.getParameter("date");
        String q = request.getParameter("q");
        model.addAttribute("view", attendanceListService.buildList(parseDate(date), q, null));
        model.addAttribute("error", ex.getMessage());
        return "attendance :: content";
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
