package com.admtechhub.maestrohr.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/attendance.html. Processes the two Thymeleaf fragments
 * ({@code content} and {@code table}) directly through the engine with a hand-built
 * {@link AttendanceListView}, so it validates the markup parses and every expression
 * evaluates (fragment names, the date picker value, the status-chip
 * {@code th:classappend}/count rendering, the hidden status carrier, the status-badge
 * {@code th:classappend}, the empty {@code th:if}) without needing security, the MVC
 * stack, or a tenant context. Mirrors {@link LeaveTemplateRenderTest}.
 */
@SpringBootTest
class AttendanceTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static List<AttendanceListView.StatusChip> chips(String active) {
        return List.of(
                new AttendanceListView.StatusChip("", "All", 12, active == null),
                new AttendanceListView.StatusChip("PRESENT", "Present", 8, "PRESENT".equals(active)),
                new AttendanceListView.StatusChip("LATE", "Late", 2, "LATE".equals(active)),
                new AttendanceListView.StatusChip("ABSENT", "Absent", 1, "ABSENT".equals(active)),
                new AttendanceListView.StatusChip("HALF_DAY", "Half Day", 1, "HALF_DAY".equals(active)));
    }

    private static List<AttendanceListView.EmployeeOption> markEmployees() {
        return List.of(
                new AttendanceListView.EmployeeOption(UUID.randomUUID(), "Tayo Shonibare (E001)"),
                new AttendanceListView.EmployeeOption(UUID.randomUUID(), "Amaka Obi (E002)"));
    }

    private static AttendanceListView viewWithRows() {
        AttendanceListView.Row present = new AttendanceListView.Row(
                UUID.randomUUID(), UUID.randomUUID(), "Tayo Shonibare", "TS",
                "09:05", "17:30", "09:05", "17:30", "8.25", "PRESENT", "Present", "success");
        AttendanceListView.Row absent = new AttendanceListView.Row(
                UUID.randomUUID(), UUID.randomUUID(), "Amaka Obi", "AO",
                "—", "—", "", "", "—", "ABSENT", "Absent", "error");
        return new AttendanceListView(
                List.of(present, absent), 2,
                "2026-06-15", "Monday, 15 June 2026", null, null, chips(null), markEmployees());
    }

    @Test
    void contentFragment_rendersHeaderFiltersAndResults() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("attendance", Set.of("content"), ctx);

        assertTrue(html.contains("Attendance"), "header title");
        assertTrue(html.contains("type=\"date\""), "date picker present");
        assertTrue(html.contains("value=\"2026-06-15\""), "date picker carries the selected day");
        assertTrue(html.contains("/htmx/attendance/table"), "filters swap the table fragment");
        assertTrue(html.contains("Tayo Shonibare"), "first roster row (results inserted into content)");
    }

    @Test
    void contentFragment_rendersMarkAttendanceForm() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("attendance", Set.of("content"), ctx);

        assertTrue(html.contains("Mark Attendance"), "Mark Attendance trigger present");
        assertTrue(html.contains("hx-post=\"/htmx/attendance/mark\""), "form posts to the mark endpoint");
        assertTrue(html.contains("data-confirm-absent"), "ABSENT confirm hook attribute present");
        assertTrue(html.contains("name=\"employeeId\""), "employee picker present");
        assertTrue(html.contains("Tayo Shonibare (E001)"), "employee option populated from the roster");
    }

    @Test
    void contentFragment_rendersResultBanners() {
        Context successCtx = new Context();
        successCtx.setVariable("view", viewWithRows());
        successCtx.setVariable("success", "Attendance saved for Amaka Obi on 2026-06-15 (ABSENT).");
        String successHtml = templateEngine.process("attendance", Set.of("content"), successCtx);
        assertTrue(successHtml.contains("Attendance saved for Amaka Obi"), "success banner rendered");

        Context errorCtx = new Context();
        errorCtx.setVariable("view", viewWithRows());
        errorCtx.setVariable("error", "Cannot mark attendance for a future date");
        String errorHtml = templateEngine.process("attendance", Set.of("content"), errorCtx);
        assertTrue(errorHtml.contains("Cannot mark attendance for a future date"), "error banner rendered");
    }

    @Test
    void tableFragment_rendersDayChipsCountsAndStatusBadges() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("attendance", Set.of("table"), ctx);

        assertTrue(html.contains("Monday, 15 June 2026"), "selected day heading");
        assertTrue(html.contains("All"), "All chip");
        assertTrue(html.contains("Present"), "Present chip / badge");
        assertTrue(html.contains("Half Day"), "Half Day chip");
        assertTrue(html.contains(">12<"), "All chip day count");
        assertTrue(html.contains("bg-primary text-white border-primary"), "selected chip styling (All)");
        assertTrue(html.contains("bg-secondary-container"), "present row uses the success badge colour");
        assertTrue(html.contains("bg-error-container"), "absent row uses the error badge colour");
        assertTrue(html.contains("name=\"status\""), "hidden status carrier present");
        assertTrue(html.contains("09:05"), "clock-in time rendered");
        assertFalse(html.contains("No attendance records found"), "non-empty view hides the empty state");
    }

    @Test
    void tableFragment_rendersPerRowEditForm() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("attendance", Set.of("table"), ctx);

        assertTrue(html.contains("</summary>"), "per-row Edit <details>/<summary> trigger present");
        assertTrue(html.contains("hx-post=\"/htmx/attendance/mark\""), "Edit form posts to the mark endpoint");
        assertTrue(html.contains("name=\"employeeId\""), "Edit form carries the row's employee id");
        assertTrue(html.contains("data-confirm-absent"), "ABSENT confirm hook attribute present");
        // The PRESENT row's status select pre-selects PRESENT via the shared statusOptions fragment.
        assertTrue(html.contains("value=\"PRESENT\" selected"), "current status pre-selected in the Edit form");
    }

    @Test
    void tableFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new AttendanceListView(
                List.of(), 0, "2026-06-15", "Monday, 15 June 2026", "missing", null, chips(null), markEmployees()));

        String html = templateEngine.process("attendance", Set.of("table"), ctx);

        assertTrue(html.contains("No attendance records found"), "empty state shown when there are no rows");
        assertTrue(html.contains("bg-primary text-white border-primary"), "All chip is selected when status is null");
    }

    // ── Monthly Calendar (Step B) ─────────────────────────────────────────────────

    private static List<AttendanceCalendarView.EmployeeOption> employeeOptions(UUID selectedId) {
        return List.of(
                new AttendanceCalendarView.EmployeeOption(selectedId, "Jane Doe (E001)", "Engineering"),
                new AttendanceCalendarView.EmployeeOption(UUID.randomUUID(), "Amaka Obi (E002)", "HR"));
    }

    @Test
    void calendarFragment_noEmployeeSelected_rendersPromptAndPicker() {
        Context ctx = new Context();
        ctx.setVariable("view", new AttendanceCalendarView(
                employeeOptions(UUID.randomUUID()), null, null, false,
                2026, 6, "June 2026", 2026, 5, 2026, 7,
                List.of(), 0L, 0L, null));

        String html = templateEngine.process("attendance", Set.of("calendar"), ctx);

        assertTrue(html.contains("Select an employee"), "prompt shown when no employee selected");
        assertTrue(html.contains("Amaka Obi (E002)"), "employee picker is populated");
        assertTrue(html.contains("June 2026"), "active month label rendered");
        // Thymeleaf HTML-escapes the '&' in the hx-get attribute value to '&amp;'.
        assertTrue(html.contains("/htmx/attendance/calendar?year=2026&amp;month=5"), "prev-month nav target");
        assertTrue(html.contains("/htmx/attendance/calendar?year=2026&amp;month=7"), "next-month nav target");
        assertTrue(html.contains("border-primary text-primary"), "Calendar tab is the active tab");
        assertFalse(html.contains("recorded days"), "summary hidden when no employee selected");
    }

    @Test
    void calendarFragment_withEmployee_rendersGridSummaryAndSymbols() {
        UUID empId = UUID.randomUUID();
        List<AttendanceCalendarView.DayCell> cells = List.of(
                new AttendanceCalendarView.DayCell(null, true, null, null, null, "", false, false),
                new AttendanceCalendarView.DayCell(1, false, "PRESENT", "Present", "success", "✓", false, false),
                new AttendanceCalendarView.DayCell(2, false, "ABSENT", "Absent", "error", "✗", false, false),
                new AttendanceCalendarView.DayCell(3, false, "ON_LEAVE", "On Leave", "neutral", "L", false, false),
                new AttendanceCalendarView.DayCell(4, false, null, null, null, "", true, true));

        Context ctx = new Context();
        ctx.setVariable("view", new AttendanceCalendarView(
                employeeOptions(empId), empId, "Jane Doe", true,
                2026, 6, "June 2026", 2026, 5, 2026, 7,
                cells, 18L, 20L, 90));

        String html = templateEngine.process("attendance", Set.of("calendar"), ctx);

        assertTrue(html.contains("Jane Doe"), "selected employee name in summary");
        assertTrue(html.contains("recorded days"), "present/recorded summary rendered");
        assertTrue(html.contains("90%"), "present rate rendered");
        assertTrue(html.contains("grid-cols-7"), "seven-column month grid");
        assertTrue(html.contains("✓"), "present symbol rendered");
        assertTrue(html.contains("L"), "on-leave symbol rendered (not blank)");
        assertTrue(html.contains("bg-secondary-container"), "present cell uses the success colour");
        assertTrue(html.contains("invisible"), "leading blank cell hidden but space-preserving");
        assertFalse(html.contains("Select an employee</p>"), "prompt hidden once an employee is selected");
    }

    @Test
    void calendarFragment_nullRate_rendersDash() {
        UUID empId = UUID.randomUUID();
        Context ctx = new Context();
        ctx.setVariable("view", new AttendanceCalendarView(
                employeeOptions(empId), empId, "Jane Doe", true,
                2026, 6, "June 2026", 2026, 5, 2026, 7,
                List.of(new AttendanceCalendarView.DayCell(1, false, null, null, null, "", false, false)),
                0L, 0L, null));

        String html = templateEngine.process("attendance", Set.of("calendar"), ctx);

        assertTrue(html.contains("—"), "rate shows an em dash when there are no recorded days");
    }
}
