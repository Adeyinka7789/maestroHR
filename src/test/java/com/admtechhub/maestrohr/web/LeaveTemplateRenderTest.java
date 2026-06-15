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
 * Render test for templates/leave.html. Processes the two Thymeleaf fragments
 * ({@code content} and {@code table}) directly through the engine with a hand-built
 * {@link LeaveListView}, so it validates the markup parses and every expression
 * evaluates (fragment names, the status-chip {@code th:classappend}/count rendering,
 * the hidden status carrier, the status-badge {@code th:classappend}, the empty
 * {@code th:if}) without needing security, the MVC stack, or a tenant context.
 * Pairs with the controller wiring exercised at runtime; a template typo would
 * otherwise only surface in the browser.
 */
@SpringBootTest
class LeaveTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static List<LeaveListView.StatusChip> chips(String active) {
        return List.of(
                new LeaveListView.StatusChip("", "All", 9, active == null),
                new LeaveListView.StatusChip("PENDING", "Pending", 3, "PENDING".equals(active)),
                new LeaveListView.StatusChip("APPROVED", "Approved", 4, "APPROVED".equals(active)),
                new LeaveListView.StatusChip("REJECTED", "Rejected", 2, "REJECTED".equals(active)));
    }

    private static LeaveListView viewWithRows() {
        LeaveListView.Row pending = new LeaveListView.Row(
                UUID.randomUUID(), "Tayo Shonibare", "TS", "Annual Leave",
                "02 Jun 2025", "06 Jun 2025", 5, "Family event",
                "PENDING", "Pending", "warn", "01 Jun 2025");
        LeaveListView.Row approved = new LeaveListView.Row(
                UUID.randomUUID(), "Amaka Obi", "AO", "Sick Leave",
                "10 May 2025", "11 May 2025", 2, "Flu",
                "APPROVED", "Approved", "success", "09 May 2025");
        return new LeaveListView(List.of(pending, approved), 2, null, "PENDING", chips("PENDING"));
    }

    @Test
    void contentFragment_rendersHeaderSearchAndResults() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("leave", Set.of("content"), ctx);

        assertTrue(html.contains("Leave Management"), "header title");
        assertTrue(html.contains("/htmx/leave/table"), "search swaps the table fragment");
        assertTrue(html.contains("Tayo Shonibare"), "first request row (results inserted into content)");
        assertTrue(html.contains("Annual Leave"), "leave type rendered");
    }

    @Test
    void tableFragment_rendersChipsCountsAndStatusBadges() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("leave", Set.of("table"), ctx);

        assertTrue(html.contains("All"), "All chip");
        assertTrue(html.contains("Approved"), "Approved chip / badge");
        assertTrue(html.contains("Rejected"), "Rejected chip");
        assertTrue(html.contains(">9<"), "All chip tenant-wide count");
        assertTrue(html.contains("bg-primary text-white border-primary"), "selected chip styling (Pending)");
        assertTrue(html.contains("bg-secondary-container"), "approved row uses the success badge colour");
        assertTrue(html.contains("name=\"status\""), "hidden status carrier present");
        assertTrue(html.contains("Family event"), "reason rendered");
        assertFalse(html.contains("No leave requests found"), "non-empty view hides the empty state");
    }

    @Test
    void tableFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new LeaveListView(List.of(), 0, "missing", null, chips(null)));

        String html = templateEngine.process("leave", Set.of("table"), ctx);

        assertTrue(html.contains("No leave requests found"), "empty state shown when there are no rows");
        assertTrue(html.contains("bg-primary text-white border-primary"), "All chip is selected when status is null");
    }
}
