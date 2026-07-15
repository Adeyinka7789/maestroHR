package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AccountDeletionQueries;
import com.admtechhub.maestrohr.platform.AccountDeletionQueries.OwnedCompany;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service account/company management for the profile page. Lists the caller's companies and
 * applies the two destructive actions — <b>leave</b> (remove only the caller's login) and
 * <b>delete</b> (soft-delete the whole company). The subject is always the authenticated email
 * passed in by the controller (never a request param), and every action re-verifies that the caller
 * actually belongs to (and, for delete, owns) the target company, so the {@code tenantId} in the URL
 * is never trusted on its own — no IDOR surface.
 *
 * <p>Deletion is guarded by a two-factor confirmation matching the product decision: the caller must
 * re-enter their password AND type the exact company name. The soft-delete itself, the membership
 * removal, and the company listing all run through the privileged
 * {@link AccountDeletionQueries} (cross-tenant / two-table writes); the password check reads the hash
 * via {@link AuthBootstrapQueries}. Restore and the 90-day purge live with the rest of the trash
 * machinery (super-admin trash page + {@code SoftDeleteCleanupJob}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountDeletionQueries accountDeletionQueries;
    private final AuthBootstrapQueries authBootstrapQueries;
    private final PasswordEncoder passwordEncoder;

    /** The caller's companies, with the one they're currently signed into flagged. */
    public AccountView listCompanies(String email, String currentTenantId) {
        List<OwnedCompany> owned = accountDeletionQueries.findCompaniesForEmail(email);
        List<AccountView.CompanyRow> rows = owned.stream()
                .map(c -> toRow(c, currentTenantId))
                .toList();
        return new AccountView(rows, rows.size() > 1);
    }

    private AccountView.CompanyRow toRow(OwnedCompany c, String currentTenantId) {
        boolean current = c.tenantId().toString().equals(currentTenantId);
        boolean owner = "SYSTEM_ADMIN".equals(c.role());
        boolean sole = c.memberCount() <= 1;
        return new AccountView.CompanyRow(
                c.tenantId().toString(), c.companyName(), formatRole(c.role()),
                current, owner, c.memberCount(), sole);
    }

    /**
     * Remove the caller's login from a company they belong to. Blocked when they are the only
     * member (that would orphan the company — they must delete it instead). @return the company name
     * (for the confirmation banner). Throws {@link IllegalArgumentException} on any guard failure.
     */
    public String leaveCompany(String email, UUID tenantId) {
        OwnedCompany company = requireMembership(email, tenantId);
        if (company.memberCount() <= 1) {
            throw new IllegalArgumentException(
                    "You are the only member of \"" + company.companyName()
                            + "\". Delete the company instead of leaving it.");
        }
        int removed = accountDeletionQueries.leaveCompany(email, tenantId);
        if (removed == 0) {
            throw new IllegalArgumentException("You are not a member of that company.");
        }
        log.info("User left a company (membership removed)");
        return company.companyName();
    }

    /**
     * Soft-delete a whole company the caller owns, after verifying the confirmation gate: the caller
     * must be its SYSTEM_ADMIN, re-enter their correct password, and type the exact company name.
     * @return the company name. Throws {@link IllegalArgumentException} on any guard failure.
     */
    public String deleteCompany(String email, UUID tenantId, String typedName, String password) {
        OwnedCompany company = requireMembership(email, tenantId);
        if (!"SYSTEM_ADMIN".equals(company.role())) {
            throw new IllegalArgumentException("Only the company owner can delete it.");
        }
        if (password == null || password.isBlank() || !passwordMatches(email, password)) {
            throw new IllegalArgumentException("Your password is incorrect.");
        }
        if (typedName == null || !typedName.trim().equals(company.companyName())) {
            throw new IllegalArgumentException(
                    "The company name you typed doesn't match. Type \"" + company.companyName() + "\" exactly.");
        }
        boolean deleted = accountDeletionQueries.softDeleteCompany(tenantId);
        if (!deleted) {
            throw new IllegalArgumentException(
                    "That company could not be deleted — it may already have been deleted.");
        }
        log.info("Company soft-deleted via self-service (90-day retention started)");
        return company.companyName();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    /** The caller's membership row for a company, or an error if they don't belong to it. */
    private OwnedCompany requireMembership(String email, UUID tenantId) {
        return accountDeletionQueries.findCompaniesForEmail(email).stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("You are not a member of that company."));
    }

    private boolean passwordMatches(String email, String password) {
        return authBootstrapQueries.findUserByEmail(email)
                .map(u -> passwordEncoder.matches(password, u.passwordHash()))
                .orElse(false);
    }

    /** "SYSTEM_ADMIN" → "System Admin". Display only. */
    private String formatRole(String role) {
        if (role == null || role.isBlank()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : role.toLowerCase(Locale.ENGLISH).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
