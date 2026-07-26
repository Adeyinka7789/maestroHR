package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.gl.GlDtos.JournalView;
import com.admtechhub.maestrohr.gl.GlDtos.RunOption;
import com.admtechhub.maestrohr.gl.GlExportService.ExportFormat;
import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * GL &amp; Accounting Export page: pick a finalized payroll run, preview its balanced journal, and
 * download it as a generic or QuickBooks-shaped CSV. Gated by {@link SubscriptionFeature#BASIC_PAYROLL}.
 */
@Controller
@RequiredArgsConstructor
public class GlExportController {

    private final GlExportService glExportService;
    private final FeatureAccessService featureAccessService;

    private static final String[] ROLES = {
            "ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    @GetMapping("/htmx/gl-export")
    public String glExport(@RequestHeader(value = "HX-Request", required = false) String htmx,
                           @RequestParam(value = "runId", required = false) UUID runId,
                           Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();

        List<RunOption> runs = glExportService.listFinalizedRuns();
        model.addAttribute("runs", runs);

        UUID selected = runId != null ? runId : (runs.isEmpty() ? null : runs.get(0).runId());
        model.addAttribute("selectedRunId", selected);
        if (selected != null) {
            JournalView journal = glExportService.buildJournal(selected);
            model.addAttribute("journal", journal);
        }
        return "gl-export :: content";
    }

    /** Download the run's journal as CSV (generic or QuickBooks). Plain GET — a normal link. */
    @GetMapping("/gl-export/{runId}/download")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.BASIC_PAYROLL)
    public ResponseEntity<byte[]> download(@PathVariable UUID runId,
                                           @RequestParam(defaultValue = "GENERIC") String format) {
        ExportFormat fmt = parseFormat(format);
        byte[] csv = glExportService.exportCsv(runId, fmt);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(glExportService.fileName(runId, fmt)).build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    // ── failure rendering ──────────────────────────────────────────────────────────

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("runs", glExportService.listFinalizedRuns());
        return "gl-export :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "GL & Accounting Export");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ExportFormat parseFormat(String raw) {
        try {
            return ExportFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ExportFormat.GENERIC;
        }
    }

    private void gate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = false;
        if (auth != null) {
            for (GrantedAuthority granted : auth.getAuthorities()) {
                for (String role : ROLES) {
                    if (role.equals(granted.getAuthority())) {
                        allowed = true;
                    }
                }
            }
        }
        if (!allowed) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
        featureAccessService.require(SubscriptionFeature.BASIC_PAYROLL);
    }
}
