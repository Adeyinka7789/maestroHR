package com.admtechhub.maestrohr.document;

/**
 * Categories of employee document held in the vault. Persisted as a string
 * ({@code @Enumerated(STRING)}) and mirrored by the {@code chk_employee_documents_type}
 * CHECK constraint in V34 — keep the two in sync when adding a value.
 */
public enum DocumentType {
    PASSPORT,
    NIN,
    BVN,
    CERTIFICATE,
    CONTRACT,
    WORK_PERMIT,
    OTHER
}
