package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.document.DocumentResponseDTO;
import com.admtechhub.maestrohr.document.DocumentService;
import com.admtechhub.maestrohr.document.DocumentType;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Employee self-service "My Documents" vault (Option 3 — server-rendered fragment), companion to
 * {@link LoanSelfController} / {@link AttendanceSelfController}. The EMPLOYEE-facing surface: the
 * employee uploads, views and removes their own documents. HR view/manage documents from the
 * employee detail page (the Documents tab).
 *
 * <p>The employee is always resolved from the session (never a request param), so a user can only
 * ever act on their own documents — the IDOR guard. Accounts with no Employee record get a
 * friendly no-profile card. Downloads are served by the REST {@code /api/documents/{id}/download}.
 */
@Controller
@RequiredArgsConstructor
public class DocumentSelfController {

    private static final String NO_PROFILE_MESSAGE =
            "This page is for employees. Your account doesn't have an associated employee profile.";

    private final DocumentService documentService;
    private final EmployeeService employeeService;

    /** Full page: app shell on a cold visit, the populated vault under HTMX. */
    @GetMapping("/htmx/documents")
    public String myDocuments(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        return renderForCurrentEmployee(model);
    }

    /** Upload a document for the authenticated employee, then re-render the vault. */
    @PostMapping("/htmx/documents/upload")
    @RequiresFeature(SubscriptionFeature.DOCUMENT_VAULT)
    public String upload(
            @RequestParam("documentType") String documentType,
            @RequestParam(value = "expiryDate", required = false) String expiryDate,
            @RequestParam("file") MultipartFile file,
            Model model) {

        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "documents-self :: content";
        }

        try {
            DocumentType type = parseType(documentType);
            LocalDate expiry = parseOptionalDate(expiryDate);
            documentService.uploadDocument(employee.getId(), file, type, expiry, employee.getEmail());
            model.addAttribute("success", "Document uploaded.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("formError", ex.getMessage());
        }

        model.addAttribute("employeeName", employee.getFullName());
        model.addAttribute("documents", listFor(employee.getId()));
        return "documents-self :: content";
    }

    /** Delete one of the authenticated employee's own documents, then re-render. */
    @PostMapping("/htmx/documents/delete")
    @RequiresFeature(SubscriptionFeature.DOCUMENT_VAULT)
    public String delete(@RequestParam("id") java.util.UUID id, Model model) {
        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "documents-self :: content";
        }
        // IDOR guard: only delete if the document belongs to this employee.
        if (employee.getId().equals(documentService.ownerEmployeeId(id))) {
            documentService.deleteDocument(id);
            model.addAttribute("success", "Document deleted.");
        } else {
            model.addAttribute("formError", "You can only delete your own documents.");
        }
        model.addAttribute("employeeName", employee.getFullName());
        model.addAttribute("documents", listFor(employee.getId()));
        return "documents-self :: content";
    }

    private String renderForCurrentEmployee(Model model) {
        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "documents-self :: content";
        }
        model.addAttribute("employeeName", employee.getFullName());
        model.addAttribute("documents", listFor(employee.getId()));
        return "documents-self :: content";
    }

    /**
     * Feature-gate failures and unexpected state errors render in place (HTTP 200 so HTMX still
     * swaps) rather than reaching the JSON handler.
     */
    @ExceptionHandler({FeatureAccessException.class, IllegalStateException.class})
    public String handleActionFailure(RuntimeException ex, Model model) {
        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "documents-self :: content";
        }
        model.addAttribute("formError", ex.getMessage());
        model.addAttribute("employeeName", employee.getFullName());
        model.addAttribute("documents", listFor(employee.getId()));
        return "documents-self :: content";
    }

    private java.util.List<DocumentResponseDTO> listFor(java.util.UUID employeeId) {
        return documentService.listByEmployee(employeeId).stream()
                .map(DocumentResponseDTO::from)
                .toList();
    }

    private DocumentType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document type is required.");
        }
        try {
            return DocumentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid document type.");
        }
    }

    private LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Expiry date is not a valid date.");
        }
    }

    private EmployeeDetailsDTO currentEmployeeOrNull() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return employeeService.findByEmail(email);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
