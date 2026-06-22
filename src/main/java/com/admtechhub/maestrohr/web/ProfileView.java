package com.admtechhub.maestrohr.web;

import java.time.LocalDate;

/**
 * View model for the self-service profile page ({@code /htmx/profile}), built by
 * {@link ProfileService} from the authenticated user's {@code User} record and (when present)
 * their {@code Employee} record.
 *
 * <p>{@link #hasEmployee()} drives the SUPER_ADMIN degradation: accounts with no Employee record
 * (the platform owner) see only the Account + Security sections, so {@link #personal()},
 * {@link #work()} and {@link #bank()} are {@code null} for them and the template guards on the flag.
 *
 * <p>Display dates ({@code lastLoginFormatted}, {@code memberSinceFormatted}, and the Work dates)
 * are pre-formatted here for the template. {@link Personal#dateOfBirth()} is kept as a
 * {@link LocalDate} on purpose: it backs an {@code <input type="date">} whose value must be the
 * ISO {@code yyyy-MM-dd} that {@code LocalDate.toString()} emits.
 */
public record ProfileView(
        boolean hasEmployee,
        String initials,

        // ── Account Info (always present) ──────────────────────────────────────
        String email,
        String roleLabel,
        boolean active,
        String lastLoginFormatted,
        String memberSinceFormatted,

        // ── Sections that require an Employee record (null for SUPER_ADMIN) ─────
        Personal personal,
        Work work,
        Bank bank
) {

    /** Editable personal details. {@code dateOfBirth} stays a LocalDate to back the date input. */
    public record Personal(
            String firstName,
            String lastName,
            String fullName,
            String phone,
            String address,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus
    ) {}

    /** Read-only employment details — managed by HR, shown here for reference only. */
    public record Work(
            String employeeNumber,
            String jobTitle,
            String department,
            String payGrade,
            String employmentType,
            String employmentStartDateFormatted,
            String probationEndDateFormatted,
            String status
    ) {}

    /** Read-only bank details — sensitive, edited only via HR employee management. */
    public record Bank(
            String bankName,
            String bankAccountNumber,
            String bankAccountName
    ) {}
}
