package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the public careers portal path, which is deliberately session-less: the
 * caller has no tenant context, so {@link CareersService} must read PUBLISHED postings and insert
 * an application through the privileged (RLS-bypassing) {@link CareersPublicRepository}, scoped
 * explicitly to the tenant resolved from the public slug. Mirrors the fixture / cleanup conventions
 * of {@code AdminProvisioningWriteTest}.
 */
@SpringBootTest
class CareersPublicIntegrationTest {

    @Autowired private CareersService careersService;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String slug = "careers-test-" + suffix;

    private UUID tenantId;
    private UUID publishedJobId;
    private UUID draftJobId;

    @BeforeEach
    void seed() {
        TenantContext.clear(); // the public path runs with no tenant session
        // The public careers page is gated by the RECRUITMENT platform kill switch; ensure it is on
        // so this test is deterministic regardless of ambient flag state.
        jdbc.update("UPDATE platform_flags SET enabled = true WHERE name = 'RECRUITMENT'");
        tenantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, company_name, industry, company_size, subscription_plan, "
                        + "subscription_expires_at, is_active, careers_slug, careers_enabled) "
                        + "VALUES (?, ?, 'TEST', '1-10', 'FREE_TRIAL', ?, true, ?, true)",
                tenantId, "Careers Test Co " + suffix, OffsetDateTime.now().plusDays(30), slug);

        publishedJobId = insertJob("PUBLISHED", "Senior Backend Engineer " + suffix);
        draftJobId = insertJob("DRAFT", "Unlisted Draft Role " + suffix);
    }

    @AfterEach
    void cleanup() {
        // FK cascade (V55/V60) would handle this, but delete explicitly for a clean, ordered teardown.
        jdbc.update("DELETE FROM job_application_resumes WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM job_applications WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM job_postings WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        TenantContext.clear();
    }

    @Test
    void landing_listsOnlyPublishedJobs() {
        CareersView.Listing listing = careersService.loadListing(slug);

        assertEquals("Careers Test Co " + suffix, listing.company().companyName());
        assertEquals(1, listing.jobs().size(), "only PUBLISHED postings are public");
        assertEquals(publishedJobId, listing.jobs().get(0).id());
    }

    @Test
    void unknownSlug_isUnavailable() {
        assertThrows(CareersService.CareersUnavailableException.class,
                () -> careersService.loadListing("no-such-company-" + suffix));
    }

    @Test
    void disabledCareersPage_isUnavailable() {
        jdbc.update("UPDATE tenants SET careers_enabled = false WHERE id = ?", tenantId);
        assertThrows(CareersService.CareersUnavailableException.class,
                () -> careersService.loadListing(slug));
    }

    @Test
    void draftJob_isNotReachable() {
        assertThrows(CareersService.JobNotFoundException.class,
                () -> careersService.loadJob(slug, draftJobId));
    }

    @Test
    void apply_persistsApplicationAndResume_viaPrivilegedPath() {
        MockMultipartFile resume = new MockMultipartFile(
                "resume", "cv.pdf", "application/pdf", "%PDF-1.4 fake resume".getBytes());

        UUID applicationId = careersService.submitApplication(
                slug, publishedJobId, "Ada Candidate", "ada+" + suffix + "@example.com",
                "08030000000", "I would love to join.", resume);

        assertNotNull(applicationId);

        // The application row landed against the right tenant/job, sourced as WEBSITE, status NEW.
        assertEquals(1L, count(
                "SELECT count(*) FROM job_applications WHERE id = ? AND tenant_id = ? AND job_posting_id = ? "
                        + "AND status = 'NEW' AND source = 'WEBSITE'",
                applicationId, tenantId, publishedJobId));

        // The resume bytes landed in the out-of-line table, linked to the application.
        assertEquals(1L, count(
                "SELECT count(*) FROM job_application_resumes WHERE application_id = ? AND tenant_id = ?",
                applicationId, tenantId));
        assertEquals("cv.pdf", jdbc.queryForObject(
                "SELECT file_name FROM job_application_resumes WHERE application_id = ?",
                String.class, applicationId));
    }

    @Test
    void apply_rejectsNonDocumentResume() {
        MockMultipartFile image = new MockMultipartFile(
                "resume", "selfie.png", "image/png", "not a document".getBytes());

        assertThrows(CareersService.ApplicationRejectedException.class,
                () -> careersService.submitApplication(
                        slug, publishedJobId, "Bad Type", "bad+" + suffix + "@example.com",
                        null, null, image));
    }

    @Test
    void apply_enforcesPerEmailSubmissionCap() {
        String email = "repeat+" + suffix + "@example.com";
        for (int i = 0; i < 3; i++) {
            careersService.submitApplication(slug, publishedJobId, "Repeat Applicant", email,
                    null, "attempt " + i, null);
        }
        assertThrows(CareersService.ApplicationRejectedException.class,
                () -> careersService.submitApplication(slug, publishedJobId, "Repeat Applicant", email,
                        null, "one too many", null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID insertJob(String status, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job_postings (id, tenant_id, title, department, location, employment_type, "
                        + "description, status, posted_date, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Engineering', 'Lagos', 'FULL_TIME', ?, ?, CURRENT_DATE, now(), now())",
                id, tenantId, title, "Build things.", status);
        return id;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value != null ? value : 0L;
    }
}
