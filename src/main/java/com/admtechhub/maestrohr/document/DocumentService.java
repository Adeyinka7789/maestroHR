package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Employee document vault: upload / download / list / delete, plus the expiry read used by
 * {@code DocumentExpiryAlertJob}. Files live in-row as BYTEA (see V34); tenant isolation is
 * enforced by RLS + the entity {@code @SQLRestriction}, so every query here is implicitly
 * scoped to the bound tenant. Callers (controllers) are responsible for the
 * employee-vs-HR authorization decision; this service trusts its inputs within the tenant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    /** Hard ceiling on a single stored file. Kept modest because the bytes live in the row. */
    static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final EmployeeDocumentRepository documentRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public EmployeeDocument uploadDocument(UUID employeeId, MultipartFile file,
                                           DocumentType documentType, LocalDate expiryDate,
                                           String uploadedBy) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was provided.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds the 10 MB limit.");
        }
        if (documentType == null) {
            throw new IllegalArgumentException("Document type is required.");
        }

        // Confirm the employee exists in this tenant (RLS-scoped) before storing against them.
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file.", e);
        }

        EmployeeDocument document = EmployeeDocument.builder()
                .tenantId(currentTenantId())
                .employeeId(employee.getId())
                .documentType(documentType)
                .fileName(sanitizeFileName(file.getOriginalFilename()))
                .fileData(bytes)
                .fileSizeBytes(bytes.length)
                .mimeType(file.getContentType())
                .expiryDate(expiryDate)
                .uploadedBy(uploadedBy)
                .build();

        EmployeeDocument saved = documentRepository.save(document);
        log.info("Stored document {} ({} bytes) for employee {}", saved.getId(), bytes.length, employeeId);
        return saved;
    }

    /** Full entity including the BYTEA payload — used only by the download endpoint. */
    @Transactional(readOnly = true)
    public EmployeeDocument downloadDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }

    /** Metadata-only list for an employee (newest first); never loads file bytes. */
    @Transactional(readOnly = true)
    public List<EmployeeDocumentSummary> listByEmployee(UUID employeeId) {
        return documentRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        EmployeeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        documentRepository.delete(document);
        log.info("Deleted document {}", documentId);
    }

    /**
     * Documents expiring within the next {@code days} (inclusive of today). Tenant-scoped read;
     * the cross-tenant variant used by the alert job lives in
     * {@code platform.DocumentExpiryQueries} because the scheduler has no tenant session.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDocumentSummary> getExpiringDocuments(int days) {
        LocalDate today = LocalDate.now();
        return documentRepository.findByExpiryDateBetweenOrderByExpiryDateAsc(today, today.plusDays(days));
    }

    /** Owning employee of a document, for the self-service IDOR guard. */
    @Transactional(readOnly = true)
    public UUID ownerEmployeeId(UUID documentId) {
        return documentRepository.findById(documentId)
                .map(EmployeeDocument::getEmployeeId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    /** Strip any path components a client may have smuggled into the original filename. */
    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "document";
        }
        String name = original.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        return name.isEmpty() ? "document" : name;
    }
}
