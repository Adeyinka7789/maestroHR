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
 * Render test for templates/attendance-analytics.html — processes the {@code analytics}
 * fragment directly through the Thymeleaf engine with a hand-built
 * {@link AttendanceAnalyticsView}, so it validates the markup parses and every expression
 * evaluates (the export link, the summary-card rate accent, the department/employee tables,
 * the trend bar heights, the cross-template {@code attendance :: tabs} reference) without the
 * MVC stack, security, or a tenant context. Mirrors {@link AttendanceTemplateRenderTest}.
 */
@SpringBootTest
class AttendanceAnalyticsTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static AttendanceAnalyticsView viewWithData() {
        AttendanceAnalyticsView.Summary summary = new AttendanceAnalyticsView.Summary(
                184, 9, 12, 3, 7, 215, 208, 94, "success", 24, 20, 30);

        List<AttendanceAnalyticsView.DeptOption> depts = List.of(
                new AttendanceAnalyticsView.DeptOption(UUID.randomUUID().toString(), "Engineering"));

        List<AttendanceAnalyticsView.EmployeeRow> employees = List.of(
                new AttendanceAnalyticsView.EmployeeRow(
                        UUID.randomUUID(), "Amaka Obi", "E002", "Human Resources",
                        14, 1, 3, 0, 0, 78, "error"),
                new AttendanceAnalyticsView.EmployeeRow(
                        UUID.randomUUID(), "Tayo Shonibare", "E001", "Engineering",
                        20, 0, 0, 0, 0, 100, "success"));

        List<AttendanceAnalyticsView.DepartmentRow> departments = List.of(
                new AttendanceAnalyticsView.DepartmentRow(
                        "Engineering", 8, 72, 2, 4, 1, 0, 92, "success"));

        List<AttendanceAnalyticsView.TrendPoint> trend = List.of(
                new AttendanceAnalyticsView.TrendPoint("2026-06-01", "1", 20, 1, 1, 0, 0, 92, 92, false, false,
                        "01 Jun — 92% (P20 L1 A1 H0)"),
                new AttendanceAnalyticsView.TrendPoint("2026-06-06", "6", 0, 0, 0, 0, 0, null, 0, true, false,
                        "06 Jun — no records (P0 L0 A0 H0)"));

        return new AttendanceAnalyticsView(
                2026, 6, "June 2026", 2026, 5, 2026, 7,
                "2026-06-01", "2026-06-30",
                depts, null,
                summary, employees, departments, trend, 92);
    }

    @Test
    void analyticsFragment_rendersHeaderExportAndActiveTab() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithData());

        String html = templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);

        assertTrue(html.contains("Team attendance analytics"), "subtitle rendered");
        assertTrue(html.contains("Export to Excel"), "export button present");
        assertTrue(html.contains("/htmx/attendance/export"), "export link targets the download endpoint");
        assertTrue(html.contains("from=2026-06-01"), "export link carries the range start");
        assertTrue(html.contains("to=2026-06-30"), "export link carries the range end");
        assertTrue(html.contains("border-primary text-primary"), "Analytics tab is the active tab");
    }

    @Test
    void analyticsFragment_rendersSummaryCards() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithData());

        String html = templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);

        assertTrue(html.contains("Attendance rate"), "rate card label");
        assertTrue(html.contains("94%"), "company attendance rate rendered");
        assertTrue(html.contains("bg-secondary-container"), "success rate uses the success accent");
        assertTrue(html.contains(">184<"), "present total card");
        assertTrue(html.contains(">24<"), "headcount card");
    }

    @Test
    void analyticsFragment_rendersDepartmentAndEmployeeTables() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithData());

        String html = templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);

        assertTrue(html.contains("By department"), "department section heading");
        assertTrue(html.contains("Engineering"), "department row");
        assertTrue(html.contains("By employee"), "employee section heading");
        assertTrue(html.contains("Amaka Obi"), "employee row rendered");
        assertTrue(html.contains("Tayo Shonibare"), "second employee row rendered");
        assertTrue(html.contains("78%"), "low-attendance employee rate rendered");
        assertTrue(html.contains("bg-error-container"), "low rate uses the error pill colour");
        assertFalse(html.contains("No attendance records</p>"), "non-empty view hides the empty state");
    }

    @Test
    void analyticsFragment_rendersTrendBars() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithData());

        String html = templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);

        assertTrue(html.contains("Daily attendance rate"), "trend heading");
        assertTrue(html.contains("height:92%"), "trend bar height reflects the day's rate");
        assertTrue(html.contains("01 Jun — 92%"), "trend bar tooltip present");
    }

    @Test
    void analyticsFragment_emptyMonth_rendersEmptyStates() {
        AttendanceAnalyticsView empty = new AttendanceAnalyticsView(
                2026, 6, "June 2026", 2026, 5, 2026, 7,
                "2026-06-01", "2026-06-30",
                List.of(), null,
                new AttendanceAnalyticsView.Summary(0, 0, 0, 0, 0, 0, 0, null, "neutral", 0, 0, 30),
                List.of(), List.of(), List.of(), 0);

        Context ctx = new Context();
        ctx.setVariable("view", empty);

        String html = templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);

        assertTrue(html.contains("No attendance recorded this month"), "empty trend state");
        assertTrue(html.contains("No attendance records for this period"), "empty department table state");
        assertTrue(html.contains("Nothing was recorded for this month"), "empty employee table state");
        assertTrue(html.contains("—"), "null rate renders an em dash");
    }
}
