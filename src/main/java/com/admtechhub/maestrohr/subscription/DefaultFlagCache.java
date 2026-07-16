package com.admtechhub.maestrohr.subscription;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Default {@link FlagCache} with two strategies, chosen per call by whether a request is bound:
 *
 * <ul>
 *   <li><b>Request bound</b> → cache in request attributes. Loaded once per HTTP request and
 *       thrown away at its end, so a request always sees a consistent, fresh view and a page
 *       with many gated widgets hits the DB once.</li>
 *   <li><b>No request</b> (jobs, schedulers, async) → a short-TTL process-wide snapshot. Without
 *       this a tenant-sweeping job would query the flag tables once per flag per tenant; with it
 *       those calls collapse to roughly one reload per TTL window. A flag change propagates to
 *       these contexts within the TTL (default 5s), which is the intended eventual-consistency
 *       trade for jobs.</li>
 * </ul>
 *
 * <p>The TTL snapshot is shared across threads: an {@link AtomicReference} swaps the whole
 * snapshot atomically on expiry, and each snapshot's maps are thread-safe.
 */
@Component
public class DefaultFlagCache implements FlagCache {

    private static final String FLAG_CACHE_ATTR = "DefaultFlagCache.flags";
    private static final String OVERRIDE_CACHE_ATTR = "DefaultFlagCache.overrides";

    private final long ttlMillis;
    private final Clock clock;
    private final AtomicReference<Snapshot> ttlSnapshot = new AtomicReference<>();

    @Autowired
    public DefaultFlagCache(@Value("${maestrohr.flags.cache-ttl-ms:5000}") long ttlMillis) {
        this(ttlMillis, Clock.systemUTC());
    }

    /** Test seam: inject a controllable clock (and TTL) to exercise expiry deterministically. */
    DefaultFlagCache(long ttlMillis, Clock clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, PlatformFlag> flags(Supplier<Map<String, PlatformFlag>> loader) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Map<String, PlatformFlag> cache = (Map<String, PlatformFlag>)
                    attrs.getAttribute(FLAG_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cache == null) {
                cache = loader.get();
                attrs.setAttribute(FLAG_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
            }
            return cache;
        }
        return currentSnapshot().flags(loader);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<FeatureFlagOverride> override(String key, Supplier<Optional<FeatureFlagOverride>> loader) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Map<String, Optional<FeatureFlagOverride>> cache = (Map<String, Optional<FeatureFlagOverride>>)
                    attrs.getAttribute(OVERRIDE_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cache == null) {
                cache = new HashMap<>();
                attrs.setAttribute(OVERRIDE_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
            }
            return cache.computeIfAbsent(key, k -> loader.get());
        }
        return currentSnapshot().overrides.computeIfAbsent(key, k -> loader.get());
    }

    /** The live TTL snapshot, replacing it atomically when the current one has expired. */
    private Snapshot currentSnapshot() {
        long now = clock.millis();
        Snapshot current = ttlSnapshot.get();
        if (current != null && now < current.expiryMillis) {
            return current;
        }
        Snapshot fresh = new Snapshot(now + ttlMillis);
        return ttlSnapshot.compareAndSet(current, fresh) ? fresh : ttlSnapshot.get();
    }

    /** One TTL window's cached view: the flag map (loaded once) and lazily-filled overrides. */
    private static final class Snapshot {
        final long expiryMillis;
        final Map<String, Optional<FeatureFlagOverride>> overrides = new ConcurrentHashMap<>();
        private volatile Map<String, PlatformFlag> flags;

        Snapshot(long expiryMillis) {
            this.expiryMillis = expiryMillis;
        }

        Map<String, PlatformFlag> flags(Supplier<Map<String, PlatformFlag>> loader) {
            Map<String, PlatformFlag> local = flags;
            if (local == null) {
                synchronized (this) {
                    local = flags;
                    if (local == null) {
                        local = loader.get();
                        flags = local;
                    }
                }
            }
            return local;
        }
    }
}
