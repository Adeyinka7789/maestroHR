package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HR-facing careers-settings endpoints on {@link RecruitmentController} that back the
 * "Careers Page" tab, AND that the controller's {@code @RequiresFeature(RECRUITMENT)} gate is
 * enforced: an entitled tenant (a plan that includes RECRUITMENT) passes, a non-entitled tenant
 * (FREE_TRIAL) is blocked. Calls the controller bean directly so the security + feature-gate AOP
 * proxies run (as {@code AdminProvisioningWriteTest} does), with the tenant bound in
 * {@link TenantContext} the way the request filters would.
 */
@SpringBootTest
@WithMockUser(roles = "HR_ADMIN")   // not SUPER_ADMIN, so the feature gate is actually enforced
class CareersSettingsControllerTest {

    @Autowired private RecruitmentController controller;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String slug = "settings-test-" + suffix;
    private final List<UUID> createdTenants = new ArrayList<>();

    private UUID tenantId;   // entitled (PROFESSIONAL) tenant used by the happy-path tests

    @BeforeEach
    void seed() {
        // Ensure the RECRUITMENT platform kill switch is on, so the gate outcome depends only on
        // plan entitlement (what these tests exercise), not ambient flag state.
        jdbc.update("UPDATE platform_flags SET enabled = true WHERE name = 'RECRUITMENT'");
        tenantId = seedTenant("PROFESSIONAL", "ACTIVE", slug);
        TenantContext.setCurrentTenant(tenantId.toString());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID id : createdTenants) {
            jdbc.update("DELETE FROM tenant_subscriptions WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM tenants WHERE id = ?", id);
        }
    }

    @Test
    void getCareersSettings_returnsSlugAndPublicUrl() {
        CareersSettingsDTO dto = controller.getCareersSettings().getBody().getData();

        assertEquals(slug, dto.getSlug());
        assertTrue(dto.isEnabled());
        assertTrue(dto.getPublicUrl().endsWith("/careers/" + slug),
                "public URL must be app.url + /careers/{slug}");
    }

    @Test
    void updateCareersSettings_togglesEnabledAndSetsIntro() {
        CareersSettingsDTO dto = controller.updateCareersSettings(false, "Join our team " + suffix)
                .getBody().getData();

        assertFalse(dto.isEnabled());
        assertEquals("Join our team " + suffix, dto.getIntro());

        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT careers_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId));
        assertEquals("Join our team " + suffix, jdbc.queryForObject(
                "SELECT careers_intro FROM tenants WHERE id = ?", String.class, tenantId));
    }

    @Test
    void featureGate_blocksTenantWhosePlanLacksRecruitment() {
        // FREE_TRIAL does not include RECRUITMENT → the @RequiresFeature gate must reject the call.
        UUID freeTenant = seedTenant("FREE_TRIAL", "TRIALING", "free-" + suffix);
        TenantContext.setCurrentTenant(freeTenant.toString());

        assertThrows(FeatureAccessException.class, () -> controller.getCareersSettings());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Seed a tenant + an in-window subscription on the given plan/status; track it for teardown. */
    private UUID seedTenant(String plan, String status, String careersSlug) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, company_name, industry, company_size, subscription_plan, "
                        + "subscription_expires_at, is_active, careers_slug, careers_enabled) "
                        + "VALUES (?, ?, 'TEST', '1-10', ?, ?, true, ?, true)",
                id, "Settings Test Co " + suffix, plan, OffsetDateTime.now().plusDays(30), careersSlug);
        jdbc.update(
                "INSERT INTO tenant_subscriptions (tenant_id, plan, status, current_period_start, "
                        + "current_period_end, auto_renew, price_kobo, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE, 0, NOW(), NOW())",
                id, plan, status, OffsetDateTime.now(), OffsetDateTime.now().plusDays(30));
        createdTenants.add(id);
        return id;
    }
}
