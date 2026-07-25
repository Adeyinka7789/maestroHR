package com.admtechhub.maestrohr.recruitment;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Privileged (RLS-bypassing) data access for the <em>public</em> careers portal.
 *
 * <p>A careers-page request carries no JWT and no tenant context, yet it must read one specific
 * tenant's PUBLISHED postings and insert an application row against that tenant. The recruitment
 * entities are {@code @SQLRestriction}-scoped and, on the {@code maestro_app} primary, additionally
 * gated by PostgreSQL RLS — so with no session bound they would read and write nothing. Routed
 * through the privileged datasource (the same one behind {@code AuthBootstrapQueries},
 * {@code WebhookTenantResolver}, {@code TenantUserWrites}) these operations resolve regardless of
 * context, and every statement is explicitly scoped to the tenant id resolved from the public slug.
 *
 * <p>Reads are constrained to public-safe columns and PUBLISHED, non-deleted, active tenants only,
 * so this class can never leak another tenant's pipeline or an unpublished draft.
 */
@Repository
public class CareersPublicRepository {

    private final JdbcTemplate jdbc;

    public CareersPublicRepository(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    /** Global kill switch: absent row = enabled (mirrors PlatformFlagService semantics). */
    public boolean recruitmentEnabled() {
        List<Boolean> rows = jdbc.query(
                "SELECT enabled FROM platform_flags WHERE name = 'RECRUITMENT'",
                (rs, n) -> rs.getBoolean("enabled"));
        return rows.isEmpty() || Boolean.TRUE.equals(rows.get(0));
    }

    /**
     * Resolve a live, careers-enabled company by its public slug. Only active, non-deleted tenants
     * are returned — a suspended or trashed company's careers page must not be reachable.
     */
    public Optional<CareersView.Company> findCompanyBySlug(String slug) {
        List<CareersView.Company> rows = jdbc.query(
                "SELECT id, company_name, logo_url, careers_intro, careers_enabled, is_active "
                        + "FROM tenants "
                        + "WHERE careers_slug = ? AND is_active = TRUE AND deleted_at IS NULL",
                (rs, n) -> new CareersView.Company(
                        rs.getObject("id", UUID.class),
                        rs.getString("company_name"),
                        rs.getString("logo_url"),
                        rs.getString("careers_intro"),
                        rs.getBoolean("careers_enabled"),
                        rs.getBoolean("is_active")),
                slug);
        return rows.stream().findFirst();
    }

    /** All PUBLISHED postings for a tenant, newest posting first. */
    public List<CareersView.Job> findPublishedJobs(UUID tenantId) {
        return jdbc.query(JOB_SELECT + " AND status = 'PUBLISHED' ORDER BY posted_date DESC NULLS LAST, created_at DESC",
                JOB_MAPPER, tenantId);
    }

    /** A single PUBLISHED posting scoped to the tenant (so a job id from another tenant 404s). */
    public Optional<CareersView.Job> findPublishedJob(UUID tenantId, UUID jobId) {
        List<CareersView.Job> rows = jdbc.query(
                JOB_SELECT + " AND status = 'PUBLISHED' AND id = ?",
                JOB_MAPPER, tenantId, jobId);
        return rows.stream().findFirst();
    }

    private static final String JOB_SELECT =
            "SELECT id, title, department, location, employment_type, salary_range_min, salary_range_max, "
                    + "description, requirements, benefits, posted_date, closing_date "
                    + "FROM job_postings WHERE tenant_id = ?";

    private static final org.springframework.jdbc.core.RowMapper<CareersView.Job> JOB_MAPPER = (rs, n) ->
            new CareersView.Job(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("department"),
                    rs.getString("location"),
                    rs.getString("employment_type"),
                    (Long) rs.getObject("salary_range_min"),
                    (Long) rs.getObject("salary_range_max"),
                    rs.getString("description"),
                    rs.getString("requirements"),
                    rs.getString("benefits"),
                    rs.getObject("posted_date", java.time.LocalDate.class),
                    rs.getObject("closing_date", java.time.LocalDate.class));

    /**
     * How many applications this email has already submitted for this posting since {@code since}.
     * Used to reject duplicate / abusive re-submissions from the public form.
     */
    public int countRecentApplications(UUID tenantId, UUID jobId, String email, OffsetDateTime since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_applications "
                        + "WHERE tenant_id = ? AND job_posting_id = ? AND lower(applicant_email) = lower(?) "
                        + "AND created_at >= ?",
                Integer.class, tenantId, jobId, email, since);
        return count == null ? 0 : count;
    }

    /** Recipients (SYSTEM_ADMIN / HR_ADMIN) to notify when a new application arrives. */
    public List<String> findNotificationRecipients(UUID tenantId) {
        return jdbc.query(
                "SELECT email FROM users WHERE tenant_id = ? AND role IN ('SYSTEM_ADMIN', 'HR_ADMIN') AND is_active = TRUE",
                (rs, n) -> rs.getString("email"), tenantId);
    }

    // ── write ────────────────────────────────────────────────────────────────────

    /**
     * Atomically insert a public application and (optionally) its resume against {@code tenantId},
     * on one connection in a single transaction so a failed resume insert can never leave a
     * half-written application. Ids are generated in Java; {@code resume_url} is set to the
     * authenticated download path so HR can fetch the file. @return the new application id.
     */
    public UUID insertApplication(UUID tenantId, UUID jobId,
                                  String applicantName, String applicantEmail, String applicantPhone,
                                  String coverLetter,
                                  byte[] resumeBytes, String resumeFileName, String resumeContentType) {
        UUID applicationId = UUID.randomUUID();
        String resumeUrl = resumeBytes != null ? "/api/recruitment/applications/" + applicationId + "/resume" : null;

        jdbc.execute((Connection con) -> {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO job_applications "
                                + "(id, tenant_id, job_posting_id, applicant_name, applicant_email, applicant_phone, "
                                + "resume_url, cover_letter, status, source, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NEW', 'WEBSITE', now(), now())")) {
                    ps.setObject(1, applicationId);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, jobId);
                    ps.setString(4, applicantName);
                    ps.setString(5, applicantEmail);
                    ps.setString(6, applicantPhone);
                    ps.setString(7, resumeUrl);
                    ps.setString(8, coverLetter);
                    ps.executeUpdate();
                }
                if (resumeBytes != null) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO job_application_resumes "
                                    + "(id, tenant_id, application_id, file_name, content_type, size_bytes, data, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, tenantId);
                        ps.setObject(3, applicationId);
                        ps.setString(4, resumeFileName);
                        ps.setString(5, resumeContentType);
                        ps.setLong(6, resumeBytes.length);
                        ps.setBytes(7, resumeBytes);
                        ps.executeUpdate();
                    }
                }
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(previousAutoCommit);
            }
            return null;
        });
        return applicationId;
    }
}
