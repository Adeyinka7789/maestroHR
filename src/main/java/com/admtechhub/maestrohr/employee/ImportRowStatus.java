package com.admtechhub.maestrohr.employee;

/**
 * Outcome of validating a single import row during the preview phase.
 *
 * <ul>
 *   <li>{@code VALID}   — row is clean and will be imported as-is.</li>
 *   <li>{@code WARNING} — row will still be imported, but a default was applied or a soft check
 *                          flagged something the user should know about (e.g. missing date of birth,
 *                          unknown department that will be created).</li>
 *   <li>{@code ERROR}   — row is invalid and will be skipped (e.g. missing email, duplicate).</li>
 * </ul>
 */
public enum ImportRowStatus {
    VALID,
    WARNING,
    ERROR
}
