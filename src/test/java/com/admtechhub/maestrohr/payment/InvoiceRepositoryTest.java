package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link Invoice} / {@link InvoiceRepository} against the real
 * Postgres configured via {@code .env}. The focus is the {@code paystack_reference}
 * idempotency key: a UNIQUE constraint must reject a second invoice that reuses a
 * reference (this is what makes webhook de-duplication possible in Phase B).
 *
 * <p>{@code @Transactional} rolls every test back. Fixtures are tagged
 * {@code companyName = "TEST-PHASE-A …"} and references {@code TEST-PHASE-A-…} so any
 * surviving row is identifiable for manual cleanup.
 */
@SpringBootTest
@Transactional
class InvoiceRepositoryTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private EntityManager entityManager;

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Tenant persistTenant() {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-PHASE-A Invoice Tenant")
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build();
        return tenantRepository.saveAndFlush(tenant);
    }

    private Invoice invoice(Tenant tenant, String reference) {
        return Invoice.builder()
                .tenant(tenant)
                .paystackReference(reference)
                .amountKobo(75_000L)
                .status(PaymentStatus.PENDING)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .period(PaymentPeriod.MONTHLY)
                .build();
    }

    // ── Unique constraint on paystack_reference rejects duplicates ────────────
    @Test
    void duplicatePaystackReference_violatesUniqueConstraint() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        String reference = "TEST-PHASE-A-DUP-REF-001";

        invoiceRepository.saveAndFlush(invoice(tenant, reference)); // first insert: OK

        Invoice duplicate = invoice(tenant, reference);
        assertThrows(DataIntegrityViolationException.class,
                () -> invoiceRepository.saveAndFlush(duplicate),
                "a second invoice reusing the paystack reference must violate the UNIQUE constraint");
    }

    // ── Idempotency lookups used by the webhook path in Phase B ───────────────
    @Test
    void findAndExistsByPaystackReference_locateTheInvoice() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        String reference = "TEST-PHASE-A-FIND-REF-001";

        Invoice saved = invoiceRepository.saveAndFlush(invoice(tenant, reference));
        entityManager.clear();

        assertTrue(invoiceRepository.existsByPaystackReference(reference),
                "existsByPaystackReference must report a stored reference");
        var found = invoiceRepository.findByPaystackReference(reference);
        assertTrue(found.isPresent(), "findByPaystackReference must locate the invoice");
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(PaymentStatus.PENDING, found.get().getStatus());
    }
}
