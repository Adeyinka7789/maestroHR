package com.admtechhub.maestrohr.web;

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
 * Render test for templates/compliance.html — processes the {@code content} fragment through the
 * Thymeleaf engine with a hand-built {@link ComplianceDashboardView}, validating the probation
 * and document tables, the one-click confirm affordance, the summary tiles, and both the
 * feature-locked and empty states without the MVC stack or a tenant session. Mirrors
 * {@link AccountTemplateRenderTest}.
 */
@SpringBootTest
class ComplianceTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    /**
     * Renders through a mock web exchange so context-relative {@code @{/...}} link expressions
     * resolve exactly as they would under a real request (a plain Context cannot).
     */
    private String render(ComplianceDashboardView view) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("view", view);
        return templateEngine.process("compliance", Set.of("content"), ctx);
    }

    @Test
    void rendersProbationAndDocumentRows() {
        UUID empId = UUID.randomUUID();
        ComplianceDashboardView.ProbationRow probation = new ComplianceDashboardView.ProbationRow(
                empId, "Tayo Shonibare", "Marketing Lead", "12 Aug 2026", -3, "Overdue by 3d", "error");
        ComplianceDashboardView.ContractRow contract = new ComplianceDashboardView.ContractRow(
                empId, "Bola Adewale", "Security Officer", "31 Dec 2026", 21, "Ends in 21d", "warn");
        ComplianceDashboardView.DocumentRow document = new ComplianceDashboardView.DocumentRow(
                empId, "Tayo Shonibare", "Work Permit", "cerpac-2026.pdf", "30 Sep 2026", 20, "Expires in 20d", "warn");

        ComplianceDashboardView view = new ComplianceDashboardView(
                1, 0, 0, List.of(probation),
                0, 1, 0, List.of(contract),
                true, 0, 1, 0, List.of(document));

        String html = render(view);

        assertTrue(html.contains("Compliance &amp; Expiry"), "page heading");
        assertTrue(html.contains("Tayo Shonibare"), "employee name");
        assertTrue(html.contains("Overdue by 3d"), "probation bucket label");
        assertTrue(html.contains("Confirm"), "one-click confirm affordance");
        assertTrue(html.contains("/htmx/compliance/confirm/" + empId), "confirm endpoint wired with the employee id");
        assertTrue(html.contains("Fixed-term contracts ending"), "contract section heading");
        assertTrue(html.contains("Bola Adewale"), "contract employee name");
        assertTrue(html.contains("31 Dec 2026"), "contract end date");
        assertTrue(html.contains("Ends in 21d"), "contract bucket label");
        assertTrue(html.contains("Work Permit"), "document type");
        assertTrue(html.contains("cerpac-2026.pdf"), "document file name");
        assertTrue(html.contains("Expires in 20d"), "document bucket label");
    }

    @Test
    void rendersLockedAndEmptyStates() {
        // Feature off, nothing due: probation empty-state + document feature-locked notice.
        ComplianceDashboardView view = new ComplianceDashboardView(
                0, 0, 0, List.of(),
                0, 0, 0, List.of(),
                false, 0, 0, 0, List.of());

        String html = render(view);

        assertTrue(html.contains("No probation reviews due"), "probation empty state");
        assertTrue(html.contains("No fixed-term contracts ending"), "contract empty state");
        assertTrue(html.contains("isn't on your current plan"), "document feature-locked notice");
        assertFalse(html.contains("/htmx/compliance/confirm/"), "no confirm buttons when nothing is due");
    }
}
