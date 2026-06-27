package com.admtechhub.maestrohr.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, UUID> {

    /** Metadata-only list for an employee (newest first). Never loads the file bytes. */
    List<EmployeeDocumentSummary> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    /**
     * Documents whose expiry falls in the inclusive window — the tenant-scoped read behind
     * {@code DocumentService.getExpiringDocuments(days)}. Metadata only.
     */
    List<EmployeeDocumentSummary> findByExpiryDateBetweenOrderByExpiryDateAsc(LocalDate from, LocalDate to);
}
