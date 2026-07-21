package com.admtechhub.maestrohr.flags;

import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import io.github.adeyinka7789.wunmi.admin.WunmiAdminMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Describes MaestroHR's flag-override domain to the wunmi admin console (/wunmi/admin), so it
 * renders a Plan dropdown and a Tenant typeahead instead of generic free-text inputs.
 *
 * <p>The wunmi override model maps onto MaestroHR's the same way {@link com.admtechhub.maestrohr.subscription.JpaFlagStore}
 * maps it: wunmi <b>SUBJECT</b> ↔ a tenant (value = tenant UUID), wunmi <b>SEGMENT</b> ↔ a
 * subscription plan (value = {@link SubscriptionPlan} name). Those are exactly the values the
 * store persists, so overrides added through this console behave identically to ones added via
 * the /htmx/admin feature-flags modal.
 */
@Component
@RequiredArgsConstructor
public class MaestroWunmiAdminMetadata implements WunmiAdminMetadata {

    /** Cap on typeahead results — the console only needs a short pick-list. */
    private static final int SUBJECT_SEARCH_LIMIT = 20;

    private final AdminStatsQueries adminStatsQueries;

    @Override
    public String subjectLabel() {
        return "Tenant";
    }

    @Override
    public String segmentLabel() {
        return "Plan";
    }

    @Override
    public List<Option> segments() {
        return Arrays.stream(SubscriptionPlan.values())
                .map(p -> new Option(p.name(), p.getDisplayName()))
                .toList();
    }

    @Override
    public boolean supportsSubjectSearch() {
        return true;
    }

    @Override
    public List<Option> searchSubjects(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return adminStatsQueries.searchTenants(query.trim(), SUBJECT_SEARCH_LIMIT).stream()
                .map(t -> new Option(t.id().toString(), t.companyName()))
                .toList();
    }
}
