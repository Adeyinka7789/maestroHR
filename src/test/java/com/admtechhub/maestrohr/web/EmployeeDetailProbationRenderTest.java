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
 * Render test for the probation → confirmation additions to templates/employee-detail.html: the
 * On Probation / Confirmed header badge, the one-click Confirm affordance (only when
 * {@code canConfirm}), and the confirmation detail line. Renders {@code employee-detail :: content}
 * through a mock web exchange so {@code @{/...}} links resolve as under a real request.
 */
@SpringBootTest
class EmployeeDetailProbationRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(EmployeeDetailView detail) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("detail", detail);
        return templateEngine.process("employee-detail", Set.of("content"), ctx);
    }

    /** Full view with only the probation-related fields varying between cases. */
    private EmployeeDetailView view(UUID id, boolean onProbation, boolean confirmed,
                                    String confirmedFormatted, String confirmedBy,
                                    String stateLabel, String stateKind, boolean canConfirm) {
        return new EmployeeDetailView(
                id, "Tayo Shonibare", "Marketing Lead", "EMP-0001",
                "ACTIVE", "Active", "success",
                "tayo@acme.ng", "+234 801 234 5678", "14 Mar 1990", "Male", "Single", "Lagos",
                "Marketing", "Full Time", "02 Jun 2025",
                onProbation ? "12 Aug 2026" : "Completed",
                "No shift configured", "Not available",
                "GTBank", "0123456789", "Tayo Shonibare", "Not linked", true, false,
                false, "No grade assigned", null, null, null, null,
                "02 Jun 2025", "02 Jun 2025",
                true, false,
                List.of(),
                onProbation, confirmed, confirmedFormatted, confirmedBy, stateLabel, stateKind, canConfirm,
                "Permanent", "Unassigned");
    }

    @Test
    void onProbation_showsBadgeAndConfirmButton() {
        UUID id = UUID.randomUUID();
        String html = render(view(id, true, false, "—", "—", "On Probation", "warn", true));

        assertTrue(html.contains("On Probation"), "probation badge");
        assertTrue(html.contains("On probation — not yet confirmed"), "employment-details probation note");
        assertTrue(html.contains("/htmx/employee-view/" + id + "/confirm"), "confirm endpoint wired");
        assertTrue(html.contains(">\n                    Confirm\n                </button>")
                || html.contains("Confirm"), "confirm affordance rendered");
    }

    @Test
    void confirmed_showsConfirmedLine_andNoConfirmButton() {
        UUID id = UUID.randomUUID();
        String html = render(view(id, false, true, "12 Aug 2026", "hr@acme.ng", "Confirmed", "success", false));

        assertTrue(html.contains("Confirmed"), "confirmed badge/label");
        assertTrue(html.contains("12 Aug 2026"), "confirmation date");
        assertTrue(html.contains("hr@acme.ng"), "confirmed-by actor");
        assertFalse(html.contains("/htmx/employee-view/" + id + "/confirm"),
                "no confirm button once already confirmed");
    }
}
