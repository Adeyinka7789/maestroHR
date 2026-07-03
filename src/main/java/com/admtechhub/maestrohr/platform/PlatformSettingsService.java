package com.admtechhub.maestrohr.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PlatformSettingsService {

    private final JdbcTemplate jdbc;

    public PlatformSettingsService(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key) {
        List<String> rows = jdbc.query(
                "SELECT value FROM platform_settings WHERE key = ?",
                (rs, n) -> rs.getString("value"),
                key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String getOrDefault(String key, String defaultValue) {
        String val = get(key);
        return val != null ? val : defaultValue;
    }

    /**
     * Typed read with a mandatory fallback. Used by payroll calculators for statutory rates/
     * thresholds: a missing row or an unparseable value must never crash a payroll run or
     * silently zero out a deduction, so both cases log a WARN and fall back to the caller's
     * hardcoded default instead.
     */
    public long getLongOrDefault(String key, long defaultValue) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Platform setting '{}' is missing; falling back to default {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Platform setting '{}' has unparseable value '{}'; falling back to default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /** See {@link #getLongOrDefault(String, long)} — same fail-safe contract, for percentage/rate fields. */
    public double getDoubleOrDefault(String key, double defaultValue) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Platform setting '{}' is missing; falling back to default {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Platform setting '{}' has unparseable value '{}'; falling back to default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    public void set(String key, String value, String updatedBy) {
        jdbc.update(
                "INSERT INTO platform_settings (key, value, updated_by, updated_at) VALUES (?, ?, ?, now()) " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()",
                key, value, updatedBy);
    }

    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>();
        jdbc.query("SELECT key, value FROM platform_settings", rs -> {
            result.put(rs.getString("key"), rs.getString("value"));
        });
        return result;
    }

    public void setMultiple(Map<String, String> settings, String updatedBy) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            set(entry.getKey(), entry.getValue(), updatedBy);
        }
    }
}
