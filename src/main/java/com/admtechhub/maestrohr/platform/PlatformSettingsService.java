package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
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
