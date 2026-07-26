package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.disbursement.DisbursementDtos.DisbursementPageView;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.RunOption;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.StatusCount;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationResult;
import com.admtechhub.maestrohr.disbursement.DisbursementDtos.ValidationRow;
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
 * Render test for templates/disbursement.html — validates the LIVE-mode banner, run summary,
 * validation panel (OK/WARN/ERROR), disburse affordance, and empty state. Renders through a mock
 * web exchange so {@code @{/...}} links resolve.
 */
@SpringBootTest
class DisbursementTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(DisbursementPageView view) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable("view", view);
        return templateEngine.process("disbursement", Set.of("content"), ctx);
    }

    @Test
    void rendersLiveBannerValidationAndDisburse() {
        UUID runId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        ValidationResult validation = new ValidationResult(1, 1, 0, List.of(
                new ValidationRow(empId, "Okeke Michael", "EMP-1", "GTBank", "0123456789",
                        "Okeke Michael", "OK", ""),
                new ValidationRow(UUID.randomUUID(), "Ada Obi", "EMP-2", "GTBank", "0000000001",
                        "Ada Obiora", "WARN", "Name mismatch: \"Ada Obiora\" vs \"Ada Obi\"")));

        DisbursementPageView view = new DisbursementPageView(
                "LIVE", true,
                List.of(new RunOption(runId, "July 2026", "Approved")),
                runId, "July 2026", "Approved", true, 2, "₦2,000",
                List.of(new StatusCount("Pending", 2, "neutral")),
                validation);

        String html = render(view);

        assertTrue(html.contains("Direct Bank Payouts"), "page heading");
        assertTrue(html.contains("LIVE mode"), "live-mode warning banner");
        assertTrue(html.contains("Disburse via Paystack"), "disburse button (run approved)");
        assertTrue(html.contains("/htmx/disbursement/" + runId + "/disburse"), "disburse endpoint wired");
        assertTrue(html.contains("/htmx/disbursement/" + runId + "/validate"), "validate endpoint wired");
        assertTrue(html.contains("Account validation"), "validation panel");
        assertTrue(html.contains("Name mismatch"), "mismatch reason shown");
    }

    @Test
    void rendersNoRunsState() {
        DisbursementPageView view = new DisbursementPageView(
                "TEST", false, List.of(), null, "", "", false, 0, "₦0", List.of(), null);

        String html = render(view);

        assertTrue(html.contains("No approved payroll runs awaiting disbursement"), "empty state");
        assertFalse(html.contains("Disburse via Paystack"), "no disburse button when nothing selected");
    }
}
