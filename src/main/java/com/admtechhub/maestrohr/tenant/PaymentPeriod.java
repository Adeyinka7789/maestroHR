package com.admtechhub.maestrohr.tenant;

public enum PaymentPeriod {
    MONTHLY(1, "Monthly"),
    QUARTERLY(3, "Quarterly"),
    ANNUALLY(12, "Annually");

    private final int months;
    private final String displayName;

    PaymentPeriod(int months, String displayName) {
        this.months = months;
        this.displayName = displayName;
    }

    public int getMonths() { return months; }
    public String getDisplayName() { return displayName; }
}