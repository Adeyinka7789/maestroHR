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

    private static AttendanceListView viewWithRows() {
        AttendanceListView.Row present = new AttendanceListView.Row(
                UUID.randomUUID(), "Tayo Shonibare", "TS",
                "09:05", "17:30", "8.25", "PRESENT", "Present", "success");
        AttendanceListView.Row absent = new AttendanceListView.Row(
                UUID.randomUUID(), "Amaka Obi", "AO",
                "—", "—", "—", "ABSENT", "Absent", "error");
        return new AttendanceListView(
                List.of(present, absent), 2,
                "2026-06-15", "Monday, 15 June 2026", null, null, chips(null));
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
    void tableFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new AttendanceListView(
                List.of(), 0, "2026-06-15", "Monday, 15 June 2026", "missing", null, chips(null)));

        String html = templateEngine.process("attendance", Set.of("table"), ctx);

        assertTrue(html.contains("No attendance records found"), "empty state shown when there are no rows");
        assertTrue(html.contains("bg-primary text-white border-primary"), "All chip is selected when status is null");
    }
}
