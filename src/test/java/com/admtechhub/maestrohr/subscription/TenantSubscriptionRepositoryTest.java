package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TenantSubscriptionRepository} against the real Postgres
 * configured via {@code .env}. Verifies basic CRUD and tenant scoping enforced by the
 * entity's {@code @SQLRestriction} (keyed on the {@code app.current_tenant} session var).
 *
 * <p>{@code @Transactional} rolls every test back, so nothing persists. All fixtures are
 * tagged {@code companyName = "TEST-PHASE-A …"} so any row that survives a failed
 * rollback is identifiable: {@code SELECT * FROM tenants WHERE company_name LIKE 'TEST-PHASE-A %'}.
 *
 * <p>Tenant scoping here is the application-level {@code @SQLRestriction} guard; it holds
 * regardless of whether the connecting DB role also enforces row-level security (the app
 * role bypasses RLS, which is why tenant creation works with no prior tenant context).
 */
@SpringBootTest
@Transactional
class TenantSubscriptionRepositoryTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private EntityManager entityManager;

    /** Bind app.current_tenant for the rest of this (test) transaction. */
    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Tenant persistTenant(String label) {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-PHASE-A " + label)
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build();
        return tenantRepository.saveAndFlush(tenant);
    }

    private TenantSubscription persistSubscription(Tenant tenant) {
        TenantSubscription sub = TenantSubscription.builder()
                .tenant(tenant)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .period(PaymentPeriod.MONTHLY)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(OffsetDateTime.now())
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .priceKobo(75_000L)
                .build();
        return tenantSubscriptionRepository.saveAndFlush(sub);
    }

    // ── Basic CRUD ────────────────────────────────────────────────────────────
    @Test
    void save_thenFindByTenantId_returnsRow() {
        Tenant tenant = persistTenant("CRUD");
        TenantSubscription saved = persistSubscription(tenant);
        entityManager.clear(); // drop the 1st-level cache so the read hits the DB

        bindTenant(tenant.getId());
        var found = tenantSubscriptionRepository.findByTenantId(tenant.getId());

        assertTrue(found.isPresent(), "the saved subscription must be retrievable");
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(SubscriptionPlan.PROFESSIONAL, found.get().getPlan());
        assertEquals(SubscriptionStatus.ACTIVE, found.get().getStatus());
        assertEquals(75_000L, found.get().getPriceKobo());
    }

    // ── Tenant scoping: a tenant sees only its own subscription ───────────────
    @Test
    void scoping_onlyOwnTenantRowIsVisible() {
        Tenant tenantA = persistTenant("Tenant A");
        Tenant tenantB = persistTenant("Tenant B");
        persistSubscription(tenantA);
        persistSubscription(tenantB);
        entityManager.clear();

        // Bound to A: only A's row is visible.
        bindTenant(tenantA.getId());
        assertTrue(tenantSubscriptionRepository.findByTenantId(tenantA.getId()).isPresent(),
                "tenant A must see its own subscription");
        assertFalse(tenantSubscriptionRepository.findByTenantId(tenantB.getId()).isPresent(),
                "tenant A must NOT see tenant B's subscription");
        List<TenantSubscription> visibleToA = tenantSubscriptionRepository.findAll();
        assertEquals(1, visibleToA.size(), "findAll must return only the bound tenant's row");
        assertEquals(tenantA.getId(), visibleToA.get(0).getTenant().getId());

        // Bound to B: the view flips.
        bindTenant(tenantB.getId());
        assertTrue(tenantSubscriptionRepository.findByTenantId(tenantB.getId()).isPresent(),
                "tenant B must see its own subscription");
        assertFalse(tenantSubscriptionRepository.findByTenantId(tenantA.getId()).isPresent(),
                "tenant B must NOT see tenant A's subscription");
        assertEquals(1, tenantSubscriptionRepository.findAll().size());
    }

    // ── No matching tenant context → nothing is visible ───────────────────────
    @Test
    void unknownTenantContext_seesNoRows() {
        Tenant tenant = persistTenant("Isolated");
        persistSubscription(tenant);
        entityManager.clear();

        bindTenant(UUID.randomUUID()); // a tenant that owns no rows
        assertTrue(tenantSubscriptionRepository.findAll().isEmpty(),
                "an unrelated tenant context must see no subscriptions");
        assertFalse(tenantSubscriptionRepository.findByTenantId(tenant.getId()).isPresent(),
                "@SQLRestriction must hide rows owned by a different tenant");
    }
}
