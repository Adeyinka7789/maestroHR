package com.admtechhub.maestrohr.document;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadata-only projection of an {@link EmployeeDocument} for list views. Deliberately omits
 * {@code fileData} so Spring Data's generated query never selects the BYTEA column — the bytes
 * are loaded only by the download path. (This is the robust complement to the entity's lazy
 * {@code fileData}, which would otherwise require bytecode enhancement to stay unfetched.)
 */
public interface EmployeeDocumentSummary {
    UUID getId();
    UUID getEmployeeId();
    DocumentType getDocumentType();
    String getFileName();
    long getFileSizeBytes();
    String getMimeType();
    LocalDate getExpiryDate();
    String getUploadedBy();
    OffsetDateTime getCreatedAt();
}
