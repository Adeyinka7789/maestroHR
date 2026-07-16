package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MaestroHR's {@link FlagContextResolver}: the flag target is the current tenant (from
 * {@code TenantContext}) and the segment is that tenant's subscription plan name. This is the one
 * place the flag engine's context resolution touches tenant identity and billing — at extraction
 * it is the adapter a consumer swaps for their own notion of "who is asking".
 */
@Component
@RequiredArgsConstructor
public class TenantFlagContextResolver implements FlagContextResolver {

    private final SubscriptionService subscriptionService;

    @Override
    public FlagContext currentContext() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = (tenantIdStr != null && !tenantIdStr.isBlank())
                ? UUID.fromString(tenantIdStr) : null;
        if (tenantId == null) {
            return FlagContext.EMPTY;
        }
        return new FlagContext(tenantId, subscriptionService.getPlanName(tenantId));
    }
}
