package com.admtechhub.maestrohr.flags;

import io.github.adeyinka7789.wunmi.Flag;
import io.github.adeyinka7789.wunmi.FlagCache;
import io.github.adeyinka7789.wunmi.FlagOverride;
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
 * MaestroHR's {@link FlagCache} for the wunmi flag engine: request-scoped when an HTTP request is
 * bound (loaded once per request), else a short-TTL process snapshot so background jobs don't
 * query the flag tables once per flag per tenant. TTL default 5s ({@code maestrohr.flags.cache-ttl-ms}).
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

    DefaultFlagCache(long ttlMillis, Clock clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Flag> flags(Supplier<Map<String, Flag>> loader) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Map<String, Flag> cache = (Map<String, Flag>)
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
    public Optional<FlagOverride> override(String key, Supplier<Optional<FlagOverride>> loader) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Map<String, Optional<FlagOverride>> cache = (Map<String, Optional<FlagOverride>>)
                    attrs.getAttribute(OVERRIDE_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (cache == null) {
                cache = new HashMap<>();
                attrs.setAttribute(OVERRIDE_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
            }
            return cache.computeIfAbsent(key, k -> loader.get());
        }
        return currentSnapshot().overrides.computeIfAbsent(key, k -> loader.get());
    }

    private Snapshot currentSnapshot() {
        // Retry on a lost race rather than returning whatever the reference now holds: an
        // invalidate() that nulls the snapshot could otherwise land between the get and the CAS,
        // and the re-read would hand back null for callers to dereference.
        while (true) {
            long now = clock.millis();
            Snapshot current = ttlSnapshot.get();
            if (current != null && now < current.expiryMillis) {
                return current;
            }
            Snapshot fresh = new Snapshot(now + ttlMillis);
            if (ttlSnapshot.compareAndSet(current, fresh)) {
                return fresh;
            }
        }
    }

    private static final class Snapshot {
        final long expiryMillis;
        final Map<String, Optional<FlagOverride>> overrides = new ConcurrentHashMap<>();
        private volatile Map<String, Flag> flags;

        Snapshot(long expiryMillis) {
            this.expiryMillis = expiryMillis;
        }

        Map<String, Flag> flags(Supplier<Map<String, Flag>> loader) {
            Map<String, Flag> local = flags;
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
