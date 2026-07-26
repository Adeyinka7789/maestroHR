package com.admtechhub.maestrohr.overtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/overtime.html — validates the period selector, the rate-card form, the
 * computed-entry table with the draft-only approve/reject affordances, and the empty state. Renders
 * through a mock web exchange so context-relative {@code @{/...}} links resolve as under a real
 * request.
 */
@SpringBootTest
class OvertimeTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(OvertimePageView view) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("view", view);
        return templateEngine.process("overtime", Set.of("content"), ctx);
    }

    private OvertimePageView.PolicyView policy() {
        return new OvertimePageView.PolicyView("8.00", 173, "1.50", "2.00");
    }

    @Test
    void rendersEntriesWithApproveForDrafts() {
        UUID id = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        OvertimePageView.EntryRow draft = new OvertimePageView.EntryRow(
                id, empId, "Sam Bello", "Guard", "2.00", "5.00", "₦100", "₦1,300",
                "Draft", "warn", true);

        OvertimePageView view = new OvertimePageView(
                7, 2026, "July 2026", policy(), List.of(draft), 1, 0, "₦1,300", "₦0");

        String html = render(view);

        assertTrue(html.contains("Overtime &amp; Shift Allowances"), "page heading");
        assertTrue(html.contains("July 2026"), "period label");
        assertTrue(html.contains("Sam Bello"), "employee name");
        assertTrue(html.contains("Compute from attendance"), "compute button");
        assertTrue(html.contains("Overtime rate card"), "policy card");
        assertTrue(html.contains("/htmx/overtime/" + id + "/approve"), "approve endpoint wired");
        assertTrue(html.contains("/htmx/overtime/" + id + "/reject"), "reject endpoint wired");
        assertTrue(html.contains("₦1,300"), "computed overtime amount");
    }

    @Test
    void rendersEmptyStateWithNoActions() {
        OvertimePageView view = new OvertimePageView(
                7, 2026, "July 2026", policy(), List.of(), 0, 0, "₦0", "₦0");

        String html = render(view);

        assertTrue(html.contains("No overtime computed for this period"), "empty state");
        assertFalse(html.contains("/approve"), "no approve buttons when empty");
    }
}
