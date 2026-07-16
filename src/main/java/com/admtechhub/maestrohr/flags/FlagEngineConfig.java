package com.admtechhub.maestrohr.flags;

import com.admtechhub.maestrohr.subscription.JpaFlagStore;
import io.github.adeyinka7789.wunmi.FlagAuditListener;
import io.github.adeyinka7789.wunmi.FlagContextResolver;
import io.github.adeyinka7789.wunmi.FlagEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the wunmi {@link FlagEngine} that backs MaestroHR flag <b>resolution</b>. Persistence is
 * {@link JpaFlagStore} (over {@code platform_flags}) and caching is {@link DefaultFlagCache}.
 *
 * <p>The engine's own audit and context-resolver seams are left as no-ops: MaestroHR only uses the
 * engine to <i>resolve</i> flags (with an explicit tenant/plan context via
 * {@code PlatformFlagService.isEnabledForTenant}), while flag <i>management</i> and its audit trail
 * stay in {@code PlatformFlagService}.
 */
@Configuration
public class FlagEngineConfig {

    @Bean
    public FlagEngine flagEngine(JpaFlagStore flagStore, DefaultFlagCache flagCache) {
        return new FlagEngine(flagStore, flagCache, FlagAuditListener.NOOP, FlagContextResolver.EMPTY);
    }
}
