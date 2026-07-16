package com.admtechhub.maestrohr.flags;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Caching SPI for the flag engine. The engine looks up flags and overrides through this
 * interface and never touches a request context or a clock directly, so the caching strategy is
 * pluggable and — crucially — works both inside and outside an HTTP request (background jobs,
 * schedulers, async tasks).
 *
 * <p>The default {@link DefaultFlagCache} caches per-request when a request is bound and falls
 * back to a short-TTL process cache otherwise, so a job sweeping every tenant no longer hits the
 * database once per flag per tenant. Part of the extractable core (future {@code wunmi} library).
 *
 * <p>Each method is a get-or-load: on a miss the supplied loader runs and its result is cached
 * for the current scope; on a hit the loader is not invoked.
 */
public interface FlagCache {

    /**
     * All flags keyed by name. Loaded once per scope via {@code loader} on a miss — the engine
     * resolves a single flag by {@code map.get(name)}, so one bulk load serves every check in
     * the scope (e.g. a page rendering several gated widgets).
     */
    Map<String, PlatformFlag> flags(Supplier<Map<String, PlatformFlag>> loader);

    /**
     * A single override, memoized by {@code key} (a {@code flag|type|value} tuple). Filled
     * lazily per key rather than preloaded, since eagerly loading every override row would
     * defeat scoping the cache to what a scope actually looks up.
     */
    Optional<FeatureFlagOverride> override(String key, Supplier<Optional<FeatureFlagOverride>> loader);
}
