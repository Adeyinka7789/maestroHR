package com.admtechhub.maestrohr.web;

import java.util.List;

/**
 * View model for the self-service "Companies" section on the profile page ({@code account ::
 * companies}, lazy-loaded by profile.html). Lists every company the authenticated user belongs to
 * — one email can own several ({@code AuthService.addCompany}) — with a per-company action to leave
 * (remove just my login) or, for owners, delete the whole company (soft-delete → 90-day retention →
 * purge). Built by {@link AccountService} from the privileged
 * {@link com.admtechhub.maestrohr.platform.AccountDeletionQueries}.
 */
public record AccountView(
        List<CompanyRow> companies,
        boolean multiCompany   // true when the caller belongs to more than one company
) {

    /**
     * One company the caller belongs to.
     *
     * @param current    the company the caller is currently signed into (this session's tenant) —
     *                   deleting/leaving it signs them out
     * @param canDelete  the caller is the SYSTEM_ADMIN owner, so may delete the whole company
     * @param soleMember the caller is the only member, so "leave" is disabled (they'd orphan the
     *                   company) and they must delete it instead
     */
    public record CompanyRow(
            String tenantId,
            String companyName,
            String roleLabel,
            boolean current,
            boolean canDelete,
            long memberCount,
            boolean soleMember
    ) {}
}
