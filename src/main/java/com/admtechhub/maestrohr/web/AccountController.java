package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Self-service company management fragment ({@code /htmx/account/**}), lazy-loaded into the profile
 * page. Lists the companies the caller belongs to and applies the two destructive actions:
 * <b>leave</b> (remove only the caller's login) and <b>delete</b> (soft-delete the whole company for
 * its owner, starting the 90-day retention that ends in a super-admin-visible purge).
 *
 * <p>Not role-gated at the URL layer — it falls under {@code anyRequest().authenticated()}, because
 * every authenticated user may manage their own memberships. The security comes from
 * {@link AccountService}, which resolves the subject from the security context and re-checks
 * membership/ownership on every action (the {@code tenantId} path variable is never trusted alone).
 *
 * <p>Validation failures re-render the fragment in place with a banner (HTTP 200, HTMX skips swaps on
 * 4xx/5xx), mirroring the leave / attendance / profile self-service controllers. When the caller
 * acts on the company they are currently signed into, the action ends their access to it, so the
 * response carries an {@code HX-Redirect} to {@code /login} to force a clean re-authentication.
 */
@Controller
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AuditTrailWrites auditTrailWrites;

    @GetMapping("/htmx/account/companies")
    public String companies(Model model) {
        return render(model);
    }

    @PostMapping("/htmx/account/company/{tenantId}/leave")
    public String leave(@PathVariable UUID tenantId, HttpServletRequest request,
                        HttpServletResponse response, Model model) {
        String email = currentEmail();
        try {
            String name = accountService.leaveCompany(email, tenantId);
            audit(tenantId, email, "COMPANY_LEFT", request);
            if (isCurrentCompany(tenantId)) {
                response.setHeader("HX-Redirect", "/login");
                return render(model);
            }
            model.addAttribute("successMessage", "You have left \"" + name + "\".");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }
        return render(model);
    }

    @PostMapping("/htmx/account/company/{tenantId}/delete")
    public String delete(@PathVariable UUID tenantId,
                         @RequestParam(required = false) String companyName,
                         @RequestParam(required = false) String password,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        String email = currentEmail();
        try {
            String name = accountService.deleteCompany(email, tenantId, companyName, password);
            audit(tenantId, email, "COMPANY_DELETED", request);
            if (isCurrentCompany(tenantId)) {
                response.setHeader("HX-Redirect", "/login");
                return render(model);
            }
            model.addAttribute("successMessage",
                    "\"" + name + "\" has been deleted. It can be restored within 90 days.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }
        return render(model);
    }

    private String render(Model model) {
        model.addAttribute("view", accountService.listCompanies(currentEmail(), TenantContext.getCurrentTenant()));
        return "account :: companies";
    }

    private boolean isCurrentCompany(UUID tenantId) {
        String current = TenantContext.getCurrentTenant();
        return current != null && current.equals(tenantId.toString());
    }

    private void audit(UUID tenantId, String email, String action, HttpServletRequest request) {
        auditTrailWrites.insert(tenantId, email, action, "tenant", tenantId.toString(),
                request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
