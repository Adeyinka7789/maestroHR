package com.admtechhub.maestrohr.paystack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a bank name to its Paystack bank code. Backed by Paystack's own {@code GET /bank}
 * directory (via {@link PaystackClient#getBanks()}), cached for 24h since the list rarely
 * changes, so a real Nigerian bank not present in a hardcoded list (e.g. "GT Bank" with a
 * space) still resolves correctly. Falls back to a small hardcoded map — used only when the
 * live directory has never been fetched successfully — so a Paystack outage doesn't take
 * employee onboarding down with it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BankCodeResolver {

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    /** Last-resort map, only consulted when the live Paystack directory has never loaded. */
    private static final Map<String, String> FALLBACK_BANK_CODES = Map.ofEntries(
            Map.entry("GTBank", "058"), Map.entry("GTB", "058"), Map.entry("Guaranty Trust Bank", "058"),
            Map.entry("First Bank", "011"), Map.entry("FirstBank", "011"),
            Map.entry("UBA", "033"), Map.entry("United Bank For Africa", "033"),
            Map.entry("Access Bank", "044"), Map.entry("Access", "044"),
            Map.entry("Zenith Bank", "057"), Map.entry("Zenith", "057"),
            Map.entry("Union Bank", "032"), Map.entry("Union", "032"),
            Map.entry("FCMB", "214"), Map.entry("First City Monument Bank", "214"),
            Map.entry("Stanbic IBTC", "221"), Map.entry("Stanbic", "221"),
            Map.entry("Sterling Bank", "232"), Map.entry("Sterling", "232"),
            Map.entry("Polaris Bank", "076"), Map.entry("Polaris", "076"),
            Map.entry("Ecobank", "050"), Map.entry("Eco", "050")
    );

    private final PaystackClient paystackClient;

    private volatile Map<String, String> cachedBanks; // lower-cased name -> code
    private volatile long cachedAt = 0L;

    /** @throws IllegalArgumentException when the name matches neither the live nor fallback list. */
    public String resolveBankCode(String bankName) {
        Map<String, String> live = liveBankMap();
        String code = live.get(bankName.toLowerCase());
        if (code != null) return code;

        code = FALLBACK_BANK_CODES.get(bankName);
        if (code != null) return code;
        for (Map.Entry<String, String> entry : FALLBACK_BANK_CODES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(bankName)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("Bank not supported: " + bankName);
    }

    private Map<String, String> liveBankMap() {
        long now = System.currentTimeMillis();
        Map<String, String> snapshot = cachedBanks;
        if (snapshot != null && (now - cachedAt) < CACHE_TTL_MS) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedBanks;
            if (snapshot != null && (now - cachedAt) < CACHE_TTL_MS) {
                return snapshot;
            }
            try {
                List<PaystackClient.PaystackBank> banks = paystackClient.getBanks();
                if (banks == null || banks.isEmpty()) {
                    log.warn("Paystack bank directory unavailable/empty; using cached or fallback map");
                    return snapshot != null ? snapshot : Map.of();
                }
                Map<String, String> fresh = new HashMap<>();
                for (PaystackClient.PaystackBank b : banks) {
                    if (b.getName() != null && b.getCode() != null) {
                        fresh.put(b.getName().toLowerCase(), b.getCode());
                    }
                }
                cachedBanks = fresh;
                cachedAt = now;
                log.info("Refreshed Paystack bank directory cache: {} banks", fresh.size());
                return fresh;
            } catch (Exception e) {
                log.warn("Failed to refresh Paystack bank directory, falling back to hardcoded map: {}", e.getMessage());
                return snapshot != null ? snapshot : Map.of();
            }
        }
    }
}
