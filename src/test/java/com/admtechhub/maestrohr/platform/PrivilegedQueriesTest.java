package com.admtechhub.maestrohr.platform;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.payment.Invoice;
import com.admtechhub.maestrohr.payment.InvoiceRepository;
import com.admtechhub.maestrohr.payment.PaymentStatus;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.subscription.TenantSubscription;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E4b — proves the privileged datasource resolves bootstrap / cross-tenant reads
 * (ops 1–7) correctly <b>regardless of {@code TenantContext} / {@code app.current_tenant}
 * state</b>. Every assertion runs both with no tenant bound and with a deliberately wrong
 * tenant bound; the privileged result must be identical and correct in both.
 *
 * <p><b>Not {@code @Transactional}.</b> The privileged JdbcTemplate uses a separate
 * connection pool, so it cannot see rows from an uncommitted test transaction. Fixtures are
 * therefore committed in {@link #seed()} and removed in {@link #cleanup()} (via the
 * privileged template, which bypasses {@code @SQLRestriction} and RLS). All fixtures are
 * tagged {@code TEST-E4B} so any survivor of a failed cleanup is identifiable.
 *
 * <p>Where it is meaningful today (before the E4c {@code maestro_app} flip), a contrast
 * assertion shows the equivalent {@code @SQLRestriction}-scoped JPA read returning nothing
 * for the same input — that Hibernate-level filter is active regardless of DB role, so it
 * already demonstrates why the privileged path is needed.
 */
@SpringBootTest
class PrivilegedQueriesTest {

    @Autowired private AuthBootstrapQueries authBootstrapQueries;
    @Autowired private WebhookTenantResolver webhookTenantResolver;
    @Autowired private SubscriptionSweepQueries subscriptionSweepQueries;
    @Autowired private AdminStatsQueries adminStatsQueries;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate privilegedJdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Tenant tenantA;   // has a locked user, an invoice, an EXPIRED-active subscription
    private Tenant tenantB;   // has an unlocked user, a still-active subscription
    private String emailA;
    private String emailB;
    private String rcNumberA;
    private String customerCodeA;
    private String subscriptionCodeB;
    private String invoiceReferenceA;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        emailA = "e4b-alpha-" + suffix + "@test.local";
        emailB = "e4b-beta-" + suffix + "@test.local";
        rcNumberA = "E4B-RC-A-" + suffix;
        customerCodeA = "E4B-CUST-A-" + suffix;
        subscriptionCodeB = "E4B-SUB-B-" + suffix;
        invoiceReferenceA = "E4B-REF-A-" + suffix;

        tenantA = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-E4B Alpha " + suffix)
                .rcNumber(rcNumberA)
                .industry("TEST").companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .paystackCustomerCode(customerCodeA)
                .build());

        tenantB = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-E4B Beta " + suffix)
                .rcNumber("E4B-RC-B-" + suffix)
                .industry("TEST").companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.BASIC)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .paystackSubscriptionCode(subscriptionCodeB)
                .build());

        // Tenant A's user is locked (locked_until in the future) → counts toward lockedUsers.
        userRepository.saveAndFlush(User.builder()
                .tenantId(tenantA.getId()).email(emailA)
                .passwordHash("hash-A").role(UserRole.HR_ADMIN)
                .lockedUntil(OffsetDateTime.now().plusMinutes(30))
                .failedLoginAttempts(5)
                .build());

        userRepository.saveAndFlush(User.builder()
                .tenantId(tenantB.getId()).email(emailB)
                .passwordHash("hash-B").role(UserRole.HR_ADMIN)
                .build());

        invoiceRepository.saveAndFlush(Invoice.builder()
                .tenant(tenantA)
                .paystackReference(invoiceReferenceA)
                .amountKobo(75_000L)
                .status(PaymentStatus.PENDING)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .period(PaymentPeriod.MONTHLY)
                .build());

        // A: ACTIVE but already past its period → lapse candidate.
        tenantSubscriptionRepository.saveAndFlush(TenantSubscription.builder()
                .tenant(tenantA)
                .plan(SubscriptionPlan.PROFESSIONAL).period(PaymentPeriod.MONTHLY)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(OffsetDateTime.now().minusDays(40))
                .currentPeriodEnd(OffsetDateTime.now().minusDays(1))
                .priceKobo(75_000L)
                .build());

        // B: ACTIVE and still within its period → NOT a candidate.
        tenantSubscriptionRepository.saveAndFlush(TenantSubscription.builder()
                .tenant(tenantB)
                .plan(SubscriptionPlan.BASIC).period(PaymentPeriod.MONTHLY)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(OffsetDateTime.now())
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .priceKobo(30_000L)
                .build());

        TenantContext.clear();
    }

    @AfterEach
    void cleanup() {
        // Privileged template bypasses @SQLRestriction + RLS, so DELETEs hit every row.
        privilegedJdbc.update("DELETE FROM invoices WHERE tenant_id IN (?, ?)", tenantA.getId(), tenantB.getId());
        privilegedJdbc.update("DELETE FROM tenant_subscriptions WHERE tenant_id IN (?, ?)", tenantA.getId(), tenantB.getId());
        privilegedJdbc.update("DELETE FROM users WHERE tenant_id IN (?, ?)", tenantA.getId(), tenantB.getId());
        privilegedJdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantA.getId(), tenantB.getId());
        TenantContext.clear();
    }

    /** Run an assertion block twice: with no tenant bound, then with a wrong tenant bound. */
    private void inBothContexts(Runnable assertions) {
        TenantContext.clear();
        assertions.run();
        try {
            TenantContext.setCurrentTenant(UUID.randomUUID().toString());
            assertions.run();
        } finally {
            TenantContext.clear();
        }
    }

    // ── Op 1 / 1b / 2 / 3(read): auth bootstrap ───────────────────────────────
    @Test
    void findUserByEmail_resolvesAcrossTenants_regardlessOfContext() {
        inBothContexts(() -> {
            var a = authBootstrapQueries.findUserByEmail(emailA);
            assertTrue(a.isPresent(), "login lookup must find the user with no/other tenant bound");
            assertEquals(tenantA.getId(), a.get().tenantId());
            assertEquals("hash-A", a.get().passwordHash());

            var b = authBootstrapQueries.findUserByEmail(emailB);
            assertTrue(b.isPresent());
            assertEquals(tenantB.getId(), b.get().tenantId());
        });
    }

    @Test
    void existsUserByEmail_isCrossTenant() {
        inBothContexts(() -> {
            assertTrue(authBootstrapQueries.existsUserByEmail(emailA));
            assertTrue(authBootstrapQueries.existsUserByEmail(emailB));
            assertFalse(authBootstrapQueries.existsUserByEmail("nobody-" + suffix + "@test.local"));
        });
    }

    @Test
    void existsTenantByRcNumber_isCrossTenant() {
        inBothContexts(() -> {
            assertTrue(authBootstrapQueries.existsTenantByRcNumber(rcNumberA));
            assertFalse(authBootstrapQueries.existsTenantByRcNumber("E4B-RC-NONE-" + suffix));
        });
    }

    @Test
    void findTenantById_resolvesForLoginResponse() {
        inBothContexts(() -> {
            var t = authBootstrapQueries.findTenantById(tenantA.getId());
            assertTrue(t.isPresent());
            assertEquals("TEST-E4B Alpha " + suffix, t.get().companyName());
        });
    }

    // ── Op 4 & 5: webhook tenant resolution ───────────────────────────────────
    @Test
    void findTenantIdByInvoiceReference_resolvesWithNoSession() {
        inBothContexts(() -> {
            var id = webhookTenantResolver.findTenantIdByInvoiceReference(invoiceReferenceA);
            assertTrue(id.isPresent());
            assertEquals(tenantA.getId(), id.get());
        });

        // Contrast (valid today): the @SQLRestriction-scoped JPA read sees nothing for the
        // same reference when a different tenant is bound — that is why we need the
        // privileged path even before the maestro_app runtime flip.
        TenantContext.setCurrentTenant(UUID.randomUUID().toString());
        try {
            assertFalse(invoiceRepository.findByPaystackReference(invoiceReferenceA).isPresent(),
                    "scoped JPA read must NOT see another tenant's invoice");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void findTenantIdByCustomerCode_resolvesWithNoSession() {
        inBothContexts(() -> {
            var id = webhookTenantResolver.findTenantIdByCustomerCode(customerCodeA);
            assertTrue(id.isPresent());
            assertEquals(tenantA.getId(), id.get());
        });
    }

    @Test
    void findTenantIdBySubscriptionCode_resolvesWithNoSession() {
        inBothContexts(() -> {
            var id = webhookTenantResolver.findTenantIdBySubscriptionCode(subscriptionCodeB);
            assertTrue(id.isPresent());
            assertEquals(tenantB.getId(), id.get());
        });
    }

    // ── Op 6: lapse sweep candidate scan ──────────────────────────────────────
    @Test
    void findExpiredActiveTenantIds_seesExpiredAcrossTenants() {
        inBothContexts(() -> {
            List<UUID> candidates = subscriptionSweepQueries.findExpiredActiveTenantIds();
            assertTrue(candidates.contains(tenantA.getId()),
                    "the expired-active subscription must be a candidate");
            assertFalse(candidates.contains(tenantB.getId()),
                    "a still-active subscription must NOT be a candidate");
        });
    }

    // ── Op 7: admin aggregates ────────────────────────────────────────────────
    @Test
    void adminAggregates_spanAllTenants() {
        inBothContexts(() -> {
            assertTrue(adminStatsQueries.countTenants() >= 2);
            assertTrue(adminStatsQueries.countActiveTenants() >= 2);
            assertTrue(adminStatsQueries.countUsers() >= 2);
            assertTrue(adminStatsQueries.countLockedUsers() >= 1, "tenant A's user is locked");

            List<TenantWithUserCountDTO> tenants = adminStatsQueries.findAllTenantsWithUserCount();
            assertTrue(tenants.stream().anyMatch(t -> t.getId().equals(tenantA.getId())));
            assertTrue(tenants.stream().anyMatch(t -> t.getId().equals(tenantB.getId())));
            assertTrue(tenants.stream()
                            .filter(t -> t.getId().equals(tenantA.getId()))
                            .findFirst().orElseThrow().getUserCount() >= 1,
                    "tenant A's user must be counted");

            List<AdminStatsQueries.AdminUserRow> users = adminStatsQueries.findAllUsers();
            assertTrue(users.stream().anyMatch(u -> u.email().equals(emailA)));
            assertTrue(users.stream().anyMatch(u -> u.email().equals(emailB)));
        });
    }
}
