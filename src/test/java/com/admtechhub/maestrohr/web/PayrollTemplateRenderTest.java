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
 * Render test for templates/payroll.html and templates/payroll-detail.html. Processes
 * the Thymeleaf fragments directly through the engine with hand-built
 * {@link PayrollListView} / {@link PayrollDetailView}s, so it validates the markup parses
 * and every expression evaluates (fragment names, the status-chip
 * {@code th:classappend}/count rendering, the hidden status carrier, the status-badge
 * {@code th:classappend}, the per-row Open link, the summary cards, the entries table,
 * the gated action buttons, and the empty / notFound states) without needing security,
 * the MVC stack, or a tenant context. Mirrors {@link AttendanceTemplateRenderTest}.
 */
@SpringBootTest
class PayrollTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    // ── payroll.html (list) ────────────────────────────────────────────────────────

    private static List<PayrollListView.StatusChip> chips(String active) {
        return List.of(
                new PayrollListView.StatusChip("", "All", 6, active == null),
                new PayrollListView.StatusChip("DRAFT", "Draft", 1, "DRAFT".equals(active)),
                new PayrollListView.StatusChip("PENDING_APPROVAL", "Pending Approval", 2, "PENDING_APPROVAL".equals(active)),
                new PayrollListView.StatusChip("APPROVED", "Approved", 1, "APPROVED".equals(active)),
                new PayrollListView.StatusChip("DISBURSING", "Disbursing", 0, "DISBURSING".equals(active)),
                new PayrollListView.StatusChip("COMPLETED", "Completed", 1, "COMPLETED".equals(active)),
                new PayrollListView.StatusChip("REJECTED", "Rejected", 1, "REJECTED".equals(active)));
    }

    private static PayrollListView listWithRows() {
        PayrollListView.Row pending = new PayrollListView.Row(
                UUID.randomUUID(), "2026-06", "June 2026", 42, "₦1,234,567.00",
                "hr@acme.test", "PENDING_APPROVAL", "Pending Approval", "warn", "01 Jun 2026");
        PayrollListView.Row completed = new PayrollListView.Row(
                UUID.randomUUID(), "2026-05", "May 2026", 40, "₦1,100,000.00",
                "hr@acme.test", "COMPLETED", "Completed", "success", "01 May 2026");
        return new PayrollListView(List.of(pending, completed), 2, null, null, chips(null));
    }

    @Test
    void contentFragment_rendersHeaderSearchAndRows() {
        Context ctx = new Context();
        ctx.setVariable("view", listWithRows());

        String html = templateEngine.process("payroll", Set.of("content"), ctx);

        assertTrue(html.contains("Payroll Runs"), "header title");
        assertTrue(html.contains("type=\"search\""), "search box present");
        assertTrue(html.contains("/htmx/payroll/table"), "search swaps the table fragment");
        assertTrue(html.contains("June 2026"), "first run row (results inserted into content)");
    }

    @Test
    void tableFragment_rendersChipsCountsBadgesAndOpenLink() {
        PayrollListView view = listWithRows();
        Context ctx = new Context();
        ctx.setVariable("view", view);

        String html = templateEngine.process("payroll", Set.of("table"), ctx);

        assertTrue(html.contains("All"), "All chip");
        assertTrue(html.contains("Pending Approval"), "Pending Approval chip / badge");
        assertTrue(html.contains("Completed"), "Completed chip / badge");
        assertTrue(html.contains(">6<"), "All chip tenant-wide count");
        assertTrue(html.contains("bg-primary text-white border-primary"), "selected chip styling (All)");
        assertTrue(html.contains("bg-primary-fixed"), "pending run uses the warn badge colour");
        assertTrue(html.contains("bg-secondary-container"), "completed run uses the success badge colour");
        assertTrue(html.contains("name=\"status\""), "hidden status carrier present");
        assertTrue(html.contains("₦1,234,567.00"), "net total rendered");
        assertTrue(html.contains("/htmx/payroll/" + view.rows().get(0).id()), "row Open link targets the detail route");
        assertTrue(html.contains("hx-push-url=\"true\""), "Open link pushes the URL");
        assertFalse(html.contains("No payroll runs found"), "non-empty view hides the empty state");
    }

    @Test
    void tableFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new PayrollListView(List.of(), 0, "missing", null, chips(null)));

        String html = templateEngine.process("payroll", Set.of("table"), ctx);

        assertTrue(html.contains("No payroll runs found"), "empty state shown when there are no runs");
        assertTrue(html.contains("bg-primary text-white border-primary"), "All chip is selected when status is null");
    }

    // ── payroll-detail.html ────────────────────────────────────────────────────────

    private static PayrollDetailView detailView(boolean withEntries) {
        List<PayrollDetailView.EntryRow> rows = withEntries
                ? List.of(new PayrollDetailView.EntryRow(
                        UUID.randomUUID(), "Tayo Shonibare", "E001", "TS",
                        "₦1,000,000.00", "₦120,000.00", "₦50,000.00", "₦25,000.00",
                        "₦0.00", "₦0.00", 0, "₦680,000.00"))
                : List.of();
        return new PayrollDetailView(
                UUID.randomUUID(), "2026-06", "June 2026",
                "PENDING_APPROVAL", "Pending Approval", "warn",
                "₦42,500,000.00", "₦35,000,000.00", "₦4,200,000.00",
                "₦2,100,000.00", "₦2,600,000.00", "₦1,050,000.00", rows.size(),
                "hr@acme.test", null, "—", null,
                false, true, false, false, rows);
    }

    @Test
    void detailContentFragment_rendersHeaderSummaryAndEntries() {
        Context ctx = new Context();
        ctx.setVariable("view", detailView(true));

        String html = templateEngine.process("payroll-detail", Set.of("content"), ctx);

        assertTrue(html.contains("June 2026"), "run period header");
        assertTrue(html.contains("/htmx/payroll\""), "back link targets the run list");
        assertTrue(html.contains("Pending Approval"), "status badge label");
        assertTrue(html.contains("bg-primary-fixed"), "warn status badge colour");
        assertTrue(html.contains("hr@acme.test"), "initiated-by rendered");
        assertTrue(html.contains("Total Gross"), "gross summary card");
        assertTrue(html.contains("₦42,500,000.00"), "gross total value");
        assertTrue(html.contains("Total NHF"), "nhf summary card");
        assertTrue(html.contains("Tayo Shonibare"), "entry row employee name");
        assertTrue(html.contains("₦680,000.00"), "entry net rendered");
        assertTrue(html.contains("Submit for Approval"), "canSubmit action button rendered");
        assertTrue(html.contains("disabled"), "action buttons are disabled in Step B");
        assertFalse(html.contains("No entries yet"), "non-empty run hides the entries empty state");
    }

    @Test
    void detailContentFragment_uncomputedRun_rendersEntriesEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", detailView(false));

        String html = templateEngine.process("payroll-detail", Set.of("content"), ctx);

        assertTrue(html.contains("No entries yet"), "entries empty state for an uncomputed draft");
        assertTrue(html.contains("has not been computed"), "empty-state explanation");
    }

    @Test
    void notFoundFragment_rendersMessageAndBackLink() {
        Context ctx = new Context();
        ctx.setVariable("message", "Payroll run not found: 123");

        String html = templateEngine.process("payroll-detail", Set.of("notFound"), ctx);

        assertTrue(html.contains("Run not found"), "not-found heading");
        assertTrue(html.contains("/htmx/payroll"), "back link to the run list");
    }
}
