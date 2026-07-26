package com.admtechhub.maestrohr.analytics;

import com.admtechhub.maestrohr.analytics.AnalyticsView.BurnoutRow;
import com.admtechhub.maestrohr.analytics.AnalyticsView.DeptRcolRow;
import com.admtechhub.maestrohr.analytics.AnalyticsView.SpikeRow;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render test for templates/analytics.html — validates the RCOL cards, departmental-spike table,
 * and burnout list, plus the no-data state. Renders through a mock web exchange so {@code @{/...}}
 * links resolve.
 */
@SpringBootTest
class AnalyticsTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(AnalyticsView view) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("view", view);
        return templateEngine.process("analytics", Set.of("content"), ctx);
    }

    @Test
    void rendersRcolSpikesAndBurnout() {
        UUID empId = UUID.randomUUID();
        AnalyticsView view = new AnalyticsView(
                true, "July 2026",
                "₦11,200,000", "₦10,000,000", "₦1,000,000", "₦100,000", "₦100,000",
                42, "₦266,667",
                List.of(new DeptRcolRow("Sales", 20, "₦6,000,000", "54%")),
                true, "June 2026",
                List.of(new SpikeRow("Sales", "₦6,000,000", "₦5,080,000", "+18%", "warn", true, "incl. ₦450,000 overtime")),
                1,
                List.of(new BurnoutRow(empId, "Tayo Shonibare", "Sales", "No approved leave in 14 months", "warn")));

        String html = render(view);

        assertTrue(html.contains("Executive Growth &amp; Labor Insights"), "page heading");
        assertTrue(html.contains("Real Cost of Labor"), "RCOL card");
        assertTrue(html.contains("₦11,200,000"), "total RCOL");
        assertTrue(html.contains("Sales"), "department row");
        assertTrue(html.contains("+18%"), "spike change");
        assertTrue(html.contains("incl. ₦450,000 overtime"), "overtime attribution note");
        assertTrue(html.contains("No approved leave in 14 months"), "burnout reason");
        assertTrue(html.contains("/htmx/employee-view"), "burnout row links to the employee");
    }

    @Test
    void rendersNoDataState() {
        AnalyticsView view = new AnalyticsView(
                false, "", "₦0", "₦0", "₦0", "₦0", "₦0", 0, "₦0",
                List.of(), false, "", List.of(), 0, List.of());

        String html = render(view);

        assertTrue(html.contains("No finalized payroll run yet"), "empty state");
    }
}
