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
 * evaluates (the period toggle, the custom range inputs, the export link, the summary-card
 * rate accent, the tables, the trend bars, the cross-template {@code attendance :: tabs}
 * reference) without the MVC stack, security, or a tenant context. Mirrors
 * {@link AttendanceTemplateRenderTest}.
 */
@SpringBootTest
class AttendanceAnalyticsTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static AttendanceAnalyticsView.Summary summary(Integer rate, String kind) {
        return new AttendanceAnalyticsView.Summary(184, 9, 12, 3, 7, 215, 208, rate, kind, 24, 20, 30);
    }

    private static List<AttendanceAnalyticsView.DeptOption> depts() {
        return List.of(new AttendanceAnalyticsView.DeptOption(UUID.randomUUID().toString(), "Engineering"));
    }

    private static List<AttendanceAnalyticsView.EmployeeRow> employees() {
        return List.of(
                new AttendanceAnalyticsView.EmployeeRow(
                        UUID.randomUUID(), "Amaka Obi", "E002", "Human Resources", 14, 1, 3, 0, 0, 78, "error"),
                new AttendanceAnalyticsView.EmployeeRow(
                        UUID.randomUUID(), "Tayo Shonibare", "E001", "Engineering", 20, 0, 0, 0, 0, 100, "success"));
    }

    private static List<AttendanceAnalyticsView.DepartmentRow> departments() {
        return List.of(new AttendanceAnalyticsView.DepartmentRow(
                "Engineering", 8, 72, 2, 4, 1, 0, 92, "success"));
    }

    private static List<AttendanceAnalyticsView.TrendPoint> trend() {
        return List.of(
                new AttendanceAnalyticsView.TrendPoint("2026-06-01", "1", 20, 1, 1, 0, 0, 92, 92, false, false,
                        "1 Jun — 92% (P20 L1 A1 H0)"),
                new AttendanceAnalyticsView.TrendPoint("2026-06-06", "6", 0, 0, 0, 0, 0, null, 0, true, false,
                        "6 Jun — no records (P0 L0 A0 H0)"));
    }

    /** Month period (default) with data. */
    private static AttendanceAnalyticsView monthView() {
        return new AttendanceAnalyticsView(
                "month", false, "2026-06-01", "2026-06-30", "June 2026",
                "2026-05-01", "2026-07-01", null, null, null, null,
                depts(), null,
                summary(94, "success"), employees(), departments(), trend(), true, false, 92);
    }

    private String render(AttendanceAnalyticsView view) {
        Context ctx = new Context();
        ctx.setVariable("view", view);
        return templateEngine.process("attendance-analytics", Set.of("analytics"), ctx);
    }

    @Test
    void rendersHeaderExportPeriodToggleAndActiveTab() {
        String html = render(monthView());

        assertTrue(html.contains("Team attendance analytics"), "subtitle rendered");
        assertTrue(html.contains("Export to Excel"), "export button present");
        assertTrue(html.contains("/htmx/attendance/export"), "export link targets the download endpoint");
        assertTrue(html.contains("from=2026-06-01"), "export link carries the range start");
        assertTrue(html.contains("to=2026-06-30"), "export link carries the range end");
        assertTrue(html.contains("border-primary text-primary"), "Analytics tab is the active tab");
        // Period toggle: all four options rendered, month active.
        assertTrue(html.contains(">Day</button>"), "Day period option");
        assertTrue(html.contains(">Week</button>"), "Week period option");
        assertTrue(html.contains(">Month</button>"), "Month period option");
        assertTrue(html.contains(">Custom</button>"), "Custom period option");
        assertTrue(html.contains("period=week"), "period toggle links carry the target period");
        assertTrue(html.contains("June 2026"), "range label rendered");
    }

    @Test
    void rendersSummaryCards() {
        String html = render(monthView());

        assertTrue(html.contains("Attendance rate"), "rate card label");
        assertTrue(html.contains("94%"), "company attendance rate rendered");
        assertTrue(html.contains("bg-secondary-container"), "success rate uses the success accent");
        assertTrue(html.contains(">184<"), "present total card");
        assertTrue(html.contains(">24<"), "headcount card");
    }

    @Test
    void rendersDepartmentAndEmployeeTables() {
        String html = render(monthView());

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
    void rendersDailyTrendBars() {
        String html = render(monthView());

        assertTrue(html.contains("Daily attendance rate"), "daily trend heading");
        assertTrue(html.contains("height:92%"), "trend bar height reflects the day's rate");
        assertTrue(html.contains("1 Jun — 92%"), "trend bar tooltip present");
    }

    @Test
    void weeklyTrend_rendersWeeklyHeading() {
        AttendanceAnalyticsView view = new AttendanceAnalyticsView(
                "custom", true, "2026-01-01", "2026-06-30", "1 Jan – 30 Jun 2026",
                null, null, "2025-07-05", "2025-12-31", "2026-07-01", "2026-12-28",
                depts(), null,
                summary(88, "warn"), employees(), departments(),
                List.of(new AttendanceAnalyticsView.TrendPoint("2026-01-01", "1 Jan", 40, 2, 3, 1, 0, 89, 89, false, false,
                        "Week of 1 Jan — 89% (P40 L2 A3 H1)")),
                true, true, 89);

        String html = render(view);

        assertTrue(html.contains("Weekly attendance rate"), "weekly trend heading for a long range");
        assertTrue(html.contains("Bar height = % attended that week"), "weekly caption");
    }

    @Test
    void customPeriod_rendersFromToInputs() {
        AttendanceAnalyticsView view = new AttendanceAnalyticsView(
                "custom", true, "2026-06-10", "2026-06-20", "10 Jun – 20 Jun 2026",
                null, null, "2026-05-30", "2026-06-09", "2026-06-21", "2026-07-01",
                depts(), null,
                summary(90, "success"), employees(), departments(), trend(), true, false, 92);

        String html = render(view);

        assertTrue(html.contains("name=\"from\""), "custom From date input rendered");
        assertTrue(html.contains("name=\"to\""), "custom To date input rendered");
        assertTrue(html.contains("period=custom"), "custom controls target the custom period");
        assertTrue(html.contains("value=\"2026-06-10\""), "From input seeded with the window start");
        assertTrue(html.contains("10 Jun – 20 Jun 2026"), "custom range label");
    }

    @Test
    void dayPeriod_hidesTrend() {
        AttendanceAnalyticsView view = new AttendanceAnalyticsView(
                "day", false, "2026-06-15", "2026-06-15", "Monday, 15 Jun 2026",
                "2026-06-14", "2026-06-16", null, null, null, null,
                depts(), null,
                summary(96, "success"), employees(), departments(), List.of(), false, false, 0);

        String html = render(view);

        assertTrue(html.contains("Monday, 15 Jun 2026"), "single-day range label");
        assertFalse(html.contains("attendance rate</h2>"), "trend section hidden for a single day");
        assertTrue(html.contains("By employee"), "tables still render for a single day");
    }

    @Test
    void emptyRange_rendersEmptyStates() {
        AttendanceAnalyticsView empty = new AttendanceAnalyticsView(
                "month", false, "2026-06-01", "2026-06-30", "June 2026",
                "2026-05-01", "2026-07-01", null, null, null, null,
                List.of(), null,
                summary(null, "neutral"), List.of(), List.of(), List.of(), true, false, 0);

        String html = render(empty);

        assertTrue(html.contains("No attendance recorded in this range"), "empty trend state");
        assertTrue(html.contains("No attendance records for this period"), "empty department table state");
        assertTrue(html.contains("Nothing was recorded for this range"), "empty employee table state");
        assertTrue(html.contains("—"), "null rate renders an em dash");
    }
}
