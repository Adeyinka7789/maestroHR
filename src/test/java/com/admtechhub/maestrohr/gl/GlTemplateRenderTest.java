package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.gl.GlDtos.CostCenterView;
import com.admtechhub.maestrohr.gl.GlDtos.EmployeeAssignRow;
import com.admtechhub.maestrohr.gl.GlDtos.GlConfigView;
import com.admtechhub.maestrohr.gl.GlDtos.JournalView;
import com.admtechhub.maestrohr.gl.GlDtos.JournalView.JournalLine;
import com.admtechhub.maestrohr.gl.GlDtos.RunOption;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render tests for templates/cost-centers.html and templates/gl-export.html — validate the
 * cost-center table, GL config form, run selector, and balanced journal preview through a mock web
 * exchange (so {@code @{/...}} links resolve).
 */
@SpringBootTest
class GlTemplateRenderTest {

    @Autowired private SpringTemplateEngine templateEngine;

    private String render(String template, Map<String, Object> vars) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        IWebExchange exchange = application.buildExchange(request, response);
        WebContext ctx = new WebContext(exchange);
        vars.forEach(ctx::setVariable);
        return templateEngine.process(template, Set.of("content"), ctx);
    }

    @Test
    void costCenters_rendersTableAndConfig() {
        CostCenterView cc = new CostCenterView(
                UUID.randomUUID(), "Lekki Outlet", "LEKKI", "Lagos", "6001", true, 12);
        GlConfigView config = new GlConfigView("6000", "6100", "2000", "2100", "2200", "2300", "2400");
        EmployeeAssignRow emp = new EmployeeAssignRow(
                UUID.randomUUID(), "Tayo Shonibare", "EMP-0001", "—");

        String html = render("cost-centers", Map.of(
                "costCenters", List.of(cc), "config", config, "assignable", List.of(emp)));

        assertTrue(html.contains("Cost Centers &amp; GL Mapping"), "page heading");
        assertTrue(html.contains("Lekki Outlet"), "cost center name");
        assertTrue(html.contains("GL account mapping"), "config section");
        assertTrue(html.contains("value=\"6000\""), "salary expense account prefilled");
        assertTrue(html.contains("Bulk assign employees"), "bulk-assign card");
        assertTrue(html.contains("Tayo Shonibare"), "assignable employee listed");
        assertTrue(html.contains("/htmx/cost-centers/assign"), "assign endpoint wired");
    }

    @Test
    void glExport_rendersBalancedJournalWithDownloads() {
        UUID runId = UUID.randomUUID();
        RunOption run = new RunOption(runId, "July 2026", "Approved");
        JournalLine debit = new JournalLine("6001", "Salary & Wages Expense", "Lekki Outlet",
                100_000, 0, "₦1,000", "", "Payroll July 2026 — Lekki Outlet");
        JournalLine credit = new JournalLine("2000", "Net Pay / Bank", "",
                0, 100_000, "", "₦1,000", "Payroll July 2026");
        JournalView journal = new JournalView(runId, "July 2026", "Approved",
                List.of(debit, credit), "₦1,000", "₦1,000", true);

        String html = render("gl-export", Map.of(
                "runs", List.of(run), "selectedRunId", runId, "journal", journal));

        assertTrue(html.contains("GL &amp; Accounting Export"), "page heading");
        assertTrue(html.contains("Salary &amp; Wages Expense"), "debit line");
        assertTrue(html.contains("Balanced"), "balanced badge");
        assertTrue(html.contains("/gl-export/" + runId + "/download"), "download link wired");
        assertTrue(html.contains("format=QUICKBOOKS"), "quickbooks download");
    }
}
