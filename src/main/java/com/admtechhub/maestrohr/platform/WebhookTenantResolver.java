package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase E4b — privileged tenant-resolution for Paystack webhooks (ops 4 & 5).
 *
 * <p>A webhook request carries no tenant context, yet the handler must discover which
 * tenant a payment belongs to before binding that tenant's session and mutating its
 * billing rows. The existing native lookups bypass Hibernate's {@code @SQLRestriction} but
 * NOT PostgreSQL RLS — once the runtime role is {@code maestro_app} they would return
 * nothing. Routed through the privileged datasource they resolve regardless of context.
 *
 * <p>Each method returns only the owning {@code tenant_id}; the eventual caller (E4c) binds
 * that tenant's session and re-fetches via the scoped JPA repositories for the mutation —
 * mirroring the pattern {@code PaymentWebhookService.handleChargeSuccess} already uses.
 */
@Repository
public class WebhookTenantResolver {

    private final JdbcTemplate jdbc;

    public WebhookTenantResolver(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owning tenant of the invoice carrying this Paystack reference (charge.success, op 4). */
    public Optional<UUID> findTenantIdByInvoiceReference(String reference) {
        return single(jdbc.query(
                "SELECT tenant_id FROM invoices WHERE paystack_reference = ?",
                (rs, n) -> rs.getObject("tenant_id", UUID.class), reference));
    }

    /** Tenant for a Paystack customer code (subscription.create, invoice.payment_failed, op 5). */
    public Optional<UUID> findTenantIdByCustomerCode(String customerCode) {
        return single(jdbc.query(
                "SELECT id FROM tenants WHERE paystack_customer_code = ?",
                (rs, n) -> rs.getObject("id", UUID.class), customerCode));
    }

    /** Tenant for a Paystack subscription code (subscription.disable, op 5). */
    public Optional<UUID> findTenantIdBySubscriptionCode(String subscriptionCode) {
        return single(jdbc.query(
                "SELECT id FROM tenants WHERE paystack_subscription_code = ?",
                (rs, n) -> rs.getObject("id", UUID.class), subscriptionCode));
    }

    private Optional<UUID> single(List<UUID> rows) {
        return rows.stream().findFirst();
    }
}
