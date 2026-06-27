package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A single file in the employee document vault, stored in-row as BYTEA (see V34).
 *
 * <p>The blob ({@link #fileData}) is {@code FetchType.LAZY} so the per-employee list query
 * (which only needs metadata) never pulls every file's bytes into memory; the bytes are
 * fetched only when {@code DocumentService.downloadDocument} touches them inside a
 * transaction. Tenant isolation is enforced by the same NULLIF {@code @SQLRestriction} the
 * rest of the tenant entities use, on top of the database RLS policy.
 */
@Entity
@Table(name = "employee_documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class EmployeeDocument extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    // Lazy: list queries read metadata only; the bytes load on demand at download time.
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data", nullable = false)
    private byte[] fileData;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;
}
