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
 * Render test for templates/pay-grades.html. Processes the two Thymeleaf fragments
 * ({@code content} and {@code grid}) directly through the engine with a hand-built
 * {@link PayGradeListView}, so it validates the markup parses and every expression
 * evaluates (fragment names, the relative-pay {@code th:style}/{@code th:text}
 * concatenations, the active/empty {@code th:classappend}/{@code th:if}) without
 * needing security, the MVC stack, or a tenant context. Pairs with the controller
 * wiring exercised at runtime; a template typo would otherwise only surface in the
 * browser.
 */
@SpringBootTest
class PayGradesTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private static PayGradeListView viewWithRows() {
        PayGradeListView.Row senior = new PayGradeListView.Row(
                UUID.randomUUID(), "Senior Engineer", true, 12,
                "₦300,000", "₦100,000", "₦50,000", "₦0", "₦450,000", 100);
        PayGradeListView.Row junior = new PayGradeListView.Row(
                UUID.randomUUID(), "Junior Analyst", false, 1,
                "₦80,000", "₦20,000", "₦10,000", "₦0", "₦110,000", 24);
        return new PayGradeListView(List.of(senior, junior), 2, "₦450,000", "₦280,000", null);
    }

    @Test
    void contentFragment_rendersHeaderSummaryAndGrid() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("pay-grades", Set.of("content"), ctx);

        assertTrue(html.contains("Pay Grades"), "header title");
        assertTrue(html.contains("₦450,000"), "highest gross in summary / card");
        assertTrue(html.contains("₦280,000"), "average gross in summary");
        assertTrue(html.contains("Senior Engineer"), "first grade card");
        assertTrue(html.contains("12"), "assigned headcount");
        assertTrue(html.contains("width:100%"), "relative-pay bar width is evaluated");
        assertTrue(html.contains("/htmx/pay-grades/table"), "search swaps the grid fragment");
    }

    @Test
    void gridFragment_rendersActiveAndInactiveCards() {
        Context ctx = new Context();
        ctx.setVariable("view", viewWithRows());

        String html = templateEngine.process("pay-grades", Set.of("grid"), ctx);

        assertTrue(html.contains("Active"), "active badge");
        assertTrue(html.contains("Inactive"), "inactive badge");
        assertTrue(html.contains("employee"), "singular/plural headcount label");
        assertTrue(html.contains("width:24%"), "second card bar width");
        assertFalse(html.contains("No pay grades found"), "non-empty view hides the empty state");
    }

    @Test
    void gridFragment_emptyView_rendersEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("view", new PayGradeListView(List.of(), 0, "₦0", "₦0", "missing"));

        String html = templateEngine.process("pay-grades", Set.of("grid"), ctx);

        assertTrue(html.contains("No pay grades found"), "empty state is shown when there are no rows");
    }
}
