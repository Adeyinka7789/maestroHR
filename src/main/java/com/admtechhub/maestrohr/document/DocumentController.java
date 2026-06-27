package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST surface for the employee document vault. Every method requires the DOCUMENT_VAULT
 * feature (plan + global flag) via {@link RequiresFeature}; authentication is enforced by the
 * security chain ({@code anyRequest().authenticated()}).
 *
 * <p>Authorization model: HR roles (HR_ADMIN / FINANCE_OFFICER / SUPER_ADMIN) may act on any
 * employee's documents; an EMPLOYEE may act only on their own (the IDOR guard in
 * {@link #assertCanAccessEmployee} / {@link #assertCanAccessDocument}). The self-service HTMX
 * vault ({@code DocumentSelfController}) is the EMPLOYEE-facing UI; this controller also backs
 * the download links used there.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@RequiresFeature(SubscriptionFeature.DOCUMENT_VAULT)
public class DocumentController {

    private final DocumentService documentService;
    private final EmployeeService employeeService;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<DocumentResponseDTO>> upload(
            @RequestParam("employeeId") UUID employeeId,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam(value = "expiryDate", required = false) LocalDate expiryDate,
            @RequestParam("file") MultipartFile file) {

        assertCanAccessEmployee(employeeId);
        EmployeeDocument saved = documentService.uploadDocument(
                employeeId, file, documentType, expiryDate, currentUserEmail());
        DocumentResponseDTO dto = new DocumentResponseDTO(
                saved.getId(), saved.getEmployeeId(), saved.getDocumentType(), saved.getFileName(),
                saved.getFileSizeBytes(), saved.getMimeType(), saved.getExpiryDate(),
                saved.getUploadedBy(), saved.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document uploaded", dto));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<List<DocumentResponseDTO>>> listByEmployee(@PathVariable UUID employeeId) {
        assertCanAccessEmployee(employeeId);
        List<DocumentResponseDTO> docs = documentService.listByEmployee(employeeId).stream()
                .map(DocumentResponseDTO::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Documents retrieved", docs));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN', 'EMPLOYEE')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        assertCanAccessDocument(id);
        EmployeeDocument document = documentService.downloadDocument(id);
        MediaType mediaType = document.getMimeType() != null
                ? MediaType.parseMediaType(document.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(document.getFileName())
                .build());
        return ResponseEntity.ok().headers(headers).body(document.getFileData());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        assertCanAccessDocument(id);
        documentService.deleteDocument(id);
        return ResponseEntity.ok(ApiResponse.success("Document deleted", null));
    }

    @GetMapping("/expiring")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<DocumentResponseDTO>>> expiring(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        List<DocumentResponseDTO> docs = documentService.getExpiringDocuments(days).stream()
                .map(DocumentResponseDTO::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Expiring documents", docs));
    }

    // ── authorization helpers ──────────────────────────────────────────────────

    /** HR roles may touch any employee; an EMPLOYEE only their own record. */
    private void assertCanAccessEmployee(UUID employeeId) {
        if (isHr()) {
            return;
        }
        EmployeeDetailsDTO me = currentEmployeeOrNull();
        if (me == null || !me.getId().equals(employeeId)) {
            throw new AccessDeniedException("You can only access your own documents.");
        }
    }

    private void assertCanAccessDocument(UUID documentId) {
        if (isHr()) {
            return;
        }
        assertCanAccessEmployee(documentService.ownerEmployeeId(documentId));
    }

    private boolean isHr() {
        return hasAnyRole("ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN");
    }

    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String currentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private EmployeeDetailsDTO currentEmployeeOrNull() {
        try {
            return employeeService.findByEmail(currentUserEmail());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
