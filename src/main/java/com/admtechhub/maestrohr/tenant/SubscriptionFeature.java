package com.admtechhub.maestrohr.tenant;

import io.github.adeyinka7789.wunmi.FlagKey;

/**
 * MaestroHR's catalogue of gateable features. Doubles as the application's {@link FlagKey}
 * implementation, so the flag engine references these constants by their stable string
 * {@link #key()} ({@code == name()}) without depending on this enum.
 */
public enum SubscriptionFeature implements FlagKey {
    // Employee limits
    BASIC_EMPLOYEES,
    UNLIMITED_EMPLOYEES,

    // Payroll features
    BASIC_PAYROLL,
    ADVANCED_PAYROLL,

    // Access features
    API_ACCESS,

    // Support levels
    EMAIL_SUPPORT,
    PRIORITY_SUPPORT,

    // HR features
    LEAVE_MANAGEMENT,
    ATTENDANCE_TRACKING,
    LOAN_MANAGEMENT,
    DOCUMENT_VAULT,
    RECRUITMENT,

    // Reporting
    CUSTOM_REPORTING,

    // Notifications
    SMS_NOTIFICATIONS,

    // Hardware integration
    HARDWARE_SYNC;

    /** The engine's stable string identifier for this feature — its enum name. */
    @Override
    public String key() {
        return name();
    }
}