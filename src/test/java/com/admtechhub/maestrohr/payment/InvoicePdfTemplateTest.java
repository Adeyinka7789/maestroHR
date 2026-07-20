package com.admtechhub.maestrohr.payment;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code templates/invoice.html} and {@code templates/billing-statement.html} through
 * Thymeleaf and Flying Saucer ({@link ITextRenderer}) — exactly the pipeline
 * {@link InvoicePdfService} uses — and asserts a real PDF comes out. This catches the failure
 * mode that would otherwise only surface at runtime: Flying Saucer's XML parser rejecting
 * HTML-named entities or otherwise malformed markup in the rendered output.
 *
 * <p>Deliberately standalone (no Spring context / DB): it builds an engine matching the Spring
 * Boot defaults (HTML mode, UTF-8, {@code templates/} prefix) so it runs fast and everywhere.
 */
class InvoicePdfTemplateTest {

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static void renderToPdf(String html) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(html);
        renderer.layout();
        renderer.createPDF(out);
        byte[] pdf = out.toByteArray();
        assertTrue(pdf.length > 500, "expected a non-trivial PDF");
        String header = new String(pdf, 0, 5, StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("%PDF"), "expected a PDF magic header, got: " + header);
    }

    @Test
    void invoiceTemplate_rendersToPdf_withDiscount() {
        Context ctx = new Context();
        ctx.setVariable("reference", "SUB_bedba81b_BASIC_QUARTERLY");
        ctx.setVariable("status", "SUCCESS");
        ctx.setVariable("issuerEmail", "support@maestrohr.com");
        ctx.setVariable("companyName", "Acme & Sons Ltd");
        ctx.setVariable("rcNumber", "RC123456");
        ctx.setVariable("issueDate", "21 Jul 2026");
        ctx.setVariable("paidDate", "21 Jul 2026");
        ctx.setVariable("description", "Basic Plan – Quarterly");
        ctx.setVariable("netAmount", 71250.0);
        ctx.setVariable("discountAmount", 3750.0);
        ctx.setVariable("originalAmount", 75000.0);
        ctx.setVariable("hasDiscount", true);
        ctx.setVariable("discountLabel", "Launch promo – 5% off");

        renderToPdf(engine().process("invoice", ctx));
    }

    @Test
    void invoiceTemplate_rendersToPdf_pendingNoDiscount() {
        Context ctx = new Context();
        ctx.setVariable("reference", "SUB_0acb1bbb_ENTERPRISE_ANNUALLY");
        ctx.setVariable("status", "PENDING");
        ctx.setVariable("issuerEmail", "");
        ctx.setVariable("companyName", "—");
        ctx.setVariable("rcNumber", "");
        ctx.setVariable("issueDate", "18 Jul 2026");
        ctx.setVariable("paidDate", "—");
        ctx.setVariable("description", "Enterprise Plan – Annually");
        ctx.setVariable("netAmount", 2160000.0);
        ctx.setVariable("discountAmount", 0.0);
        ctx.setVariable("originalAmount", 2160000.0);
        ctx.setVariable("hasDiscount", false);
        ctx.setVariable("discountLabel", null);

        renderToPdf(engine().process("invoice", ctx));
    }

    @Test
    void statementTemplate_rendersToPdf_withRows() {
        Context ctx = new Context();
        ctx.setVariable("companyName", "Acme & Sons Ltd");
        ctx.setVariable("generatedDate", "22 Jul 2026");
        ctx.setVariable("invoices", List.of(
                Map.of("date", "21 Jul 2026", "reference", "SUB_bedba81b_BASIC_QUARTERLY",
                        "description", "Basic Plan – Quarterly", "status", "SUCCESS", "amount", 71250.0),
                Map.of("date", "18 Jul 2026", "reference", "SUB_0acb1bbb_ENTERPRISE_MONTHLY",
                        "description", "Enterprise Plan – Monthly", "status", "PENDING", "amount", 200000.0)));
        ctx.setVariable("invoiceCount", 2);
        ctx.setVariable("totalPaid", 71250.0);

        renderToPdf(engine().process("billing-statement", ctx));
    }

    @Test
    void statementTemplate_rendersToPdf_empty() {
        Context ctx = new Context();
        ctx.setVariable("companyName", "—");
        ctx.setVariable("generatedDate", "22 Jul 2026");
        ctx.setVariable("invoices", List.of());
        ctx.setVariable("invoiceCount", 0);
        ctx.setVariable("totalPaid", 0.0);

        renderToPdf(engine().process("billing-statement", ctx));
    }
}
