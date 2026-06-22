package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the super-admin billing console
 * (templates/admin-billing.html) and the {@code GET /api/admin/billing/overview} REST payload.
 *
 * <p>Assembled by {@link AdminBillingService} from cross-tenant privileged reads
 * ({@code AdminBillingQueries}); the MRR figures follow the {@code pricing_config} convention used
 * by the platform dashboard so the totals on the two pages reconcile. Kobo totals are exposed
 * alongside their formatted strings so API consumers get raw numbers while the template gets
 * display-ready text.
 */
public record AdminBillingView(
        long mrrTotalKobo,
        String mrrTotalFormatted,   // compact NGN, e.g. "₦1.25M"
        long activeCount,           // subscriptions with status ACTIVE
        long trialingCount,         // subscriptions with status TRIALING
        long pastDueCount,          // subscriptions PAST_DUE or EXPIRED (matches the table below)
        List<MrrByPlanRow> mrrByPlan,
        List<PastDueRow> pastDueTenants,
        List<InvoiceRow> recentInvoices
) {

    /** Per-plan MRR breakdown row. */
    public record MrrByPlanRow(
            String plan,                // raw plan name, e.g. "PROFESSIONAL"
            long tenantCount,           // active tenants on this plan
            long revenueKobo,
            String revenueFormatted     // full NGN, e.g. "₦1,500,000"
    ) {}

    /** A row of the past-due tenants table. */
    public record PastDueRow(
            UUID tenantId,
            String companyName,
            String plan,
            String status,              // PAST_DUE | EXPIRED
            String expiredLabel         // formatted current_period_end, or "—"
    ) {}

    /** A row of the platform-wide recent-invoices feed. */
    public record InvoiceRow(
            UUID id,
            UUID tenantId,
            String companyName,
            String amountFormatted,     // full NGN, e.g. "₦75,000"
            String status,
            String plan,
            String createdLabel         // formatted created_at
    ) {}
}
