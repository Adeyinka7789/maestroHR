package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * Resume file for a {@link JobApplication}, stored in-row as BYTEA (V60) — the same
 * trade-off the V34 document vault takes for small artifacts. Kept out of line from
 * {@code job_applications} so the applications list never loads file bytes; the payload is
 * {@code LAZY} and read only by the HR download endpoint.
 *
 * <p>Tenant-scoped like the rest of recruitment ({@code @SQLRestriction} + RLS). The public,
 * session-less apply path cannot persist through this entity (no tenant bound); it inserts via
 * the privileged {@link CareersPublicRepository}. This entity backs the internal (HR-entered)
 * apply path and the authenticated resume download.
 */
@Entity
@Table(name = "job_application_resumes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class JobApplicationResume extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Lazy + plain byte[] maps to Postgres BYTEA (matching V60); @Lob would force oid/BLOB.
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    private byte[] data;
}
