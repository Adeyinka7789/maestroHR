package com.admtechhub.maestrohr.flags;

/**
 * Audit SPI for the flag engine: notified whenever a flag, override, or rollout percentage
 * changes. Extracting this behind an interface keeps the engine free of any particular audit
 * system — the default {@link AuditTrailFlagListener} records to MaestroHR's audit trail, but a
 * library consumer can supply a no-op, a logger, or their own sink.
 *
 * <p>Part of the extractable core (future {@code wunmi} library).
 */
public interface FlagAuditListener {

    /** A single flag-configuration change worth recording. */
    void onFlagChanged(FlagChange change);

    /**
     * A flag-configuration change.
     *
     * @param flagName the flag affected
     * @param actor    who made the change (e.g. an admin email), may be {@code null}
     * @param detail   human-readable description of what changed
     */
    record FlagChange(String flagName, String actor, String detail) {}
}
