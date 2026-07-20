package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders subscription {@link Invoice}s to PDF via Thymeleaf + Flying Saucer, mirroring
 * {@link com.admtechhub.maestrohr.notification.PayslipGenerator}.
 *
 * <p>Both methods are {@code @Transactional(readOnly = true)} on purpose: the app runs with
 * {@code spring.jpa.open-in-view: false}, so the lazily-loaded {@link Invoice#getTenant()}
 * association must be touched inside an open persistence context. Reads are tenant-scoped by the
 * {@code @SQLRestriction} on {@link Invoice} plus RLS on the primary datasource, so a tenant can
 * only ever render its own invoices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy").withLocale(Locale.ENGLISH);

    private final InvoiceRepository invoiceRepository;
    private final TemplateEngine templateEngine;
    private final PlatformSettingsService platformSettings;

    /** Thrown when the requested reference has no invoice visible to the current tenant. */
    public static class InvoiceNotFoundException extends RuntimeException {
        public InvoiceNotFoundException(String reference) {
            super("No invoice found for reference " + reference);
        }
    }

    /**
     * Render a single tenant invoice (by its Paystack reference) to a PDF document.
     *
     * @throws InvoiceNotFoundException if no invoice with that reference is visible to the tenant.
     */
    @Transactional(readOnly = true)
    public byte[] renderInvoice(String reference) {
        Invoice inv = invoiceRepository.findByPaystackReference(reference)
                .orElseThrow(() -> new InvoiceNotFoundException(reference));

        long netKobo = inv.getAmountKobo() != null ? inv.getAmountKobo() : 0L;
        long discountKobo = inv.getDiscountKobo() != null ? inv.getDiscountKobo() : 0L;
        Tenant tenant = inv.getTenant();

        Context ctx = new Context();
        ctx.setVariable("reference", inv.getPaystackReference());
        ctx.setVariable("status", statusName(inv));
        ctx.setVariable("issuerEmail", platformSettings.getOrDefault("support_email", ""));
        ctx.setVariable("companyName", tenant != null && tenant.getCompanyName() != null ? tenant.getCompanyName() : "—");
        ctx.setVariable("rcNumber", tenant != null && tenant.getRcNumber() != null ? tenant.getRcNumber() : "");
        ctx.setVariable("issueDate", inv.getCreatedAt() != null ? DATE.format(inv.getCreatedAt()) : "—");
        ctx.setVariable("paidDate", inv.getPaidAt() != null ? DATE.format(inv.getPaidAt()) : "—");
        ctx.setVariable("description", describe(inv));
        ctx.setVariable("netAmount", netKobo / 100.0);
        ctx.setVariable("discountAmount", discountKobo / 100.0);
        ctx.setVariable("originalAmount", (netKobo + discountKobo) / 100.0);
        ctx.setVariable("hasDiscount", discountKobo > 0);
        ctx.setVariable("discountLabel", inv.getDiscountLabel());

        return toPdf(templateEngine.process("invoice", ctx), reference);
    }

    /**
     * Render a single statement PDF listing every invoice for the current tenant, newest first,
     * with a total of successful (paid) charges.
     */
    @Transactional(readOnly = true)
    public byte[] renderStatement() {
        List<Invoice> invoices = invoiceRepository.findAllByOrderByCreatedAtDesc();

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalPaidKobo = 0L;
        String companyName = "—";
        for (Invoice inv : invoices) {
            Tenant tenant = inv.getTenant();
            if (tenant != null && tenant.getCompanyName() != null) {
                companyName = tenant.getCompanyName();
            }
            long amountKobo = inv.getAmountKobo() != null ? inv.getAmountKobo() : 0L;
            if (inv.getStatus() == PaymentStatus.SUCCESS) {
                totalPaidKobo += amountKobo;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            OffsetDateTime when = inv.getPaidAt() != null ? inv.getPaidAt() : inv.getCreatedAt();
            row.put("date", when != null ? DATE.format(when) : "—");
            row.put("reference", inv.getPaystackReference());
            row.put("description", describe(inv));
            row.put("status", statusName(inv));
            row.put("amount", amountKobo / 100.0);
            rows.add(row);
        }

        Context ctx = new Context();
        ctx.setVariable("companyName", companyName);
        ctx.setVariable("generatedDate", DATE.format(OffsetDateTime.now()));
        ctx.setVariable("invoices", rows);
        ctx.setVariable("invoiceCount", rows.size());
        ctx.setVariable("totalPaid", totalPaidKobo / 100.0);

        return toPdf(templateEngine.process("billing-statement", ctx), "statement");
    }

    private String statusName(Invoice inv) {
        return (inv.getStatus() != null ? inv.getStatus() : PaymentStatus.PENDING).name();
    }

    private String describe(Invoice inv) {
        String plan = inv.getPlan() != null ? titleCase(inv.getPlan().name()) + " Plan" : "Subscription";
        return inv.getPeriod() != null ? plan + " – " + titleCase(inv.getPeriod().name()) : plan;
    }

    private String titleCase(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private byte[] toPdf(String html, String label) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render invoice PDF ({}): {}", label, e.getMessage(), e);
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }
    }
}
