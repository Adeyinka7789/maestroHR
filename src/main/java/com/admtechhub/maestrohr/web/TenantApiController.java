package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.payment.Invoice;
import com.admtechhub.maestrohr.payment.InvoicePdfService;
import com.admtechhub.maestrohr.payment.InvoiceRepository;
import com.admtechhub.maestrohr.payment.PaymentStatus;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantApiController {

    private final PlatformSettingsService platformSettings;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePdfService invoicePdfService;

    /**
     * Billing history for the current tenant, newest first — backs the settings-page
     * "Billing & Invoices" table (see {@code settings.html#loadBillingHistory}). Tenant-scoped
     * by the {@code @SQLRestriction} on {@link Invoice} plus RLS on the primary datasource.
     *
     * <p>Shape matches what the frontend renders: {@code date}, {@code id} (the Paystack
     * reference), {@code description}, {@code amount} (kobo), {@code status}. Values are placed
     * in a {@link LinkedHashMap} rather than {@link Map#of} because some fields can be null.
     */
    @GetMapping("/billing-history")
    public ResponseEntity<?> getBillingHistory() {
        List<Map<String, Object>> data = invoiceRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toBillingRow)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * Download a single invoice (identified by its Paystack reference) as a PDF. Same visibility
     * as {@link #getBillingHistory()} — tenant-scoped, so a caller can only fetch its own invoices;
     * an unknown/foreign reference yields 404.
     */
    @GetMapping("/invoices/{reference}/pdf")
    public ResponseEntity<byte[]> downloadInvoice(@PathVariable String reference) {
        byte[] pdf;
        try {
            pdf = invoicePdfService.renderInvoice(reference);
        } catch (InvoicePdfService.InvoiceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("invoice-" + reference + ".pdf").build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    /**
     * Download a single billing-statement PDF listing every invoice for the current tenant.
     * Backs the "Download All" action on the settings billing panel.
     */
    @GetMapping("/invoices/pdf")
    public ResponseEntity<byte[]> downloadStatement() {
        byte[] pdf = invoicePdfService.renderStatement();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("maestrohr-billing-statement.pdf").build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    private Map<String, Object> toBillingRow(Invoice inv) {
        Map<String, Object> row = new LinkedHashMap<>();
        // Prefer the settlement time; fall back to when the invoice stub was created.
        row.put("date", inv.getPaidAt() != null ? inv.getPaidAt() : inv.getCreatedAt());
        row.put("id", inv.getPaystackReference());
        row.put("description", describe(inv));
        row.put("amount", inv.getAmountKobo());
        // Discount detail for a fuller receipt (0 / null when none applied).
        long discountKobo = inv.getDiscountKobo() != null ? inv.getDiscountKobo() : 0L;
        row.put("discount", discountKobo);
        row.put("discountLabel", inv.getDiscountLabel());
        row.put("originalAmount", inv.getAmountKobo() + discountKobo);
        row.put("status", (inv.getStatus() != null ? inv.getStatus() : PaymentStatus.PENDING).name());
        return row;
    }

    private String describe(Invoice inv) {
        String plan = inv.getPlan() != null ? titleCase(inv.getPlan().name()) + " Plan" : "Subscription";
        return inv.getPeriod() != null ? plan + " – " + titleCase(inv.getPeriod().name()) : plan;
    }

    /** {@code PROFESSIONAL} / {@code MONTHLY} → {@code Professional} / {@code Monthly}. */
    private String titleCase(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @GetMapping("/support-contacts")
    public ResponseEntity<?> getSupportContacts() {
        return ResponseEntity.ok(Map.of(
                "supportEmail", platformSettings.getOrDefault("support_email", ""),
                "supportWhatsapp", platformSettings.getOrDefault("support_whatsapp", "")));
    }
}
