package com.admtechhub.maestrohr.subscription;

/**
 * A typed handle for a feature flag — the flag engine's public vocabulary. Callers reference a
 * flag by an enum constant that implements this interface rather than a bare string, so flag
 * names are discoverable, refactor-safe, and impossible to typo at the call site.
 *
 * <p>The engine itself stores and resolves flags by their string {@link #key()}, so it stays
 * agnostic of any particular application's flag catalogue. An application supplies its own enum
 * (e.g. {@link com.admtechhub.maestrohr.tenant.SubscriptionFeature}) implementing this interface.
 *
 * <p>Part of the extractable core (future {@code wunmi} library) — no domain dependencies.
 */
public interface FlagKey {

    /** The stable string identifier persisted in {@code platform_flags.name}. */
    String key();
}
