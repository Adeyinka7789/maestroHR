package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
public class ActiveFeaturesController {

    private final PlatformFlagService platformFlagService;
    private final FlagContextResolver contextResolver;

    /**
     * Returns the SubscriptionFeature flags enabled <b>for the current tenant</b>: full layered
     * resolution (global kill switch → per-tenant override → plan override → rollout), the same
     * {@link PlatformFlagService#isEnabledForTenant} path the page gates use — so a feature
     * disabled for this tenant (not just globally) is hidden from the nav, and nav visibility and
     * gate enforcement share one policy. A flag with no {@code platform_flags} row is disabled
     * (fail-closed), not shown. Used by layout.js to hide nav items whose feature is off.
     *
     * <p>Not entitlement-filtered: a feature the plan doesn't include but whose flag is on still
     * appears (its page shows the upgrade prompt), preserving upgrade discovery.
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<String>>> activeFeatures() {
        FlagContextResolver.FlagContext ctx = contextResolver.currentContext();
        List<String> active = Arrays.stream(SubscriptionFeature.values())
                .map(SubscriptionFeature::name)
                .filter(name -> platformFlagService.isEnabledForTenant(name, ctx.targetId(), ctx.segment()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("ok", active));
    }
}
