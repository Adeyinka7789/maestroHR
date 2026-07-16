package com.admtechhub.maestrohr.flags;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural test for {@link DefaultFlagCache}: the request-scoped path caches for the life of a
 * request, and the no-request path caches for a bounded TTL (R3) — the fix that stops background
 * jobs querying the flag tables once per flag per tenant. A controllable clock exercises expiry
 * deterministically.
 */
class DefaultFlagCacheTest {

    /** A clock whose instant the test can advance by hand. */
    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long startMillis) { this.millis = startMillis; }
        void advance(long deltaMillis) { this.millis += deltaMillis; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static Map<String, PlatformFlag> oneFlag() {
        return Map.of("F", PlatformFlag.builder().name("F").enabled(true).build());
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void noRequestContext_secondCallWithinTtl_doesNotReload() {
        MutableClock clock = new MutableClock(0);
        DefaultFlagCache cache = new DefaultFlagCache(5000L, clock);
        AtomicInteger loads = new AtomicInteger();

        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });
        clock.advance(4999);
        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });

        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void noRequestContext_afterTtlExpiry_reloads() {
        MutableClock clock = new MutableClock(0);
        DefaultFlagCache cache = new DefaultFlagCache(5000L, clock);
        AtomicInteger loads = new AtomicInteger();

        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });
        clock.advance(5000);   // reaches expiry boundary → snapshot considered stale
        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void noRequestContext_overrideMemoizedByKeyWithinTtl() {
        MutableClock clock = new MutableClock(0);
        DefaultFlagCache cache = new DefaultFlagCache(5000L, clock);
        AtomicInteger loads = new AtomicInteger();

        Optional<FeatureFlagOverride> first = cache.override("k", () -> {
            loads.incrementAndGet();
            return Optional.of(FeatureFlagOverride.builder().flagName("F").enabled(true).build());
        });
        Optional<FeatureFlagOverride> second = cache.override("k", () -> {
            loads.incrementAndGet();
            return Optional.of(FeatureFlagOverride.builder().flagName("F").enabled(false).build());
        });

        assertThat(loads.get()).isEqualTo(1);
        assertThat(first).isPresent();
        assertThat(second.get().isEnabled()).isTrue();   // the memoized first value
    }

    @Test
    void requestContext_cachesForTheRequestAndIgnoresTtlClock() {
        MutableClock clock = new MutableClock(0);
        DefaultFlagCache cache = new DefaultFlagCache(5000L, clock);
        AtomicInteger loads = new AtomicInteger();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });
        clock.advance(10_000);   // well past the TTL — irrelevant while a request is bound
        cache.flags(() -> { loads.incrementAndGet(); return oneFlag(); });

        assertThat(loads.get()).isEqualTo(1);
    }
}
