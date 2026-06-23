package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries.DeletedType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Server-rendered HTMX fragment controller for the super-admin trash page
 * ({@code /htmx/admin/deleted-records}) — the same Option-3 pattern as
 * {@link SuperAdminTenantDetailController}. GET renders the cross-tenant list of soft-deleted rows;
 * POST /restore and POST /purge act on a single row (via the privileged {@link DeletedRecordsQueries})
 * and re-render the fragment so HTMX swaps in the updated table.
 *
 * <p>Authorization is enforced at the URL layer ({@code /htmx/admin/**} → SUPER_ADMIN in
 * SecurityConfig); {@code @PreAuthorize} is the method-level backstop, so restore — and the
 * irreversible purge-now — are SUPER_ADMIN only.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminDeletedRecordsController {

    private final DeletedRecordsQueries deletedRecordsQueries;
    private final AuditTrailWrites auditTrailWrites;

    @GetMapping("/htmx/admin/deleted-records")
    public String deletedRecords(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        return render(model);
    }

    @PostMapping("/htmx/admin/deleted-records/{type}/{id}/restore")
    public String restore(
            @PathVariable DeletedType type,
            @PathVariable UUID id,
            HttpServletRequest request,
            Model model) {

        UUID tenantId = deletedRecordsQueries.restore(type, id);
        if (tenantId != null) {
            auditTrailWrites.insert(tenantId, actorEmail(), "RECORD_RESTORED", type.auditEntity(),
                    id.toString(), request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
            model.addAttribute("successMessage", "Record restored.");
        } else {
            model.addAttribute("errorMessage", "That record could not be found — it may already have been restored or purged.");
        }
        return render(model);
    }

    @PostMapping("/htmx/admin/deleted-records/{type}/{id}/purge")
    public String purge(
            @PathVariable DeletedType type,
            @PathVariable UUID id,
            HttpServletRequest request,
            Model model) {

        try {
            UUID tenantId = deletedRecordsQueries.purgeNow(type, id);
            if (tenantId != null) {
                auditTrailWrites.insert(tenantId, actorEmail(), "RECORD_PURGED", type.auditEntity(),
                        id.toString(), request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
                model.addAttribute("successMessage", "Record permanently deleted.");
            } else {
                model.addAttribute("errorMessage", "That record could not be found — it may already have been restored or purged.");
            }
        } catch (DataAccessException e) {
            // Still referenced by other rows (e.g. a department a trashed employee points at).
            model.addAttribute("errorMessage",
                    "This record is still referenced by other data and cannot be permanently deleted yet.");
        }
        return render(model);
    }

    private String render(Model model) {
        model.addAttribute("records", deletedRecordsQueries.findAllDeleted());
        return "admin-deleted-records :: content";
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
