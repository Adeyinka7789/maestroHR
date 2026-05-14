package com.admtechhub.maestrohr.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private final PricingConfigRepository pricingConfigRepository;

    @Transactional
    public void initializeDefaultPricing() {
        // Check if pricing already exists
        if (pricingConfigRepository.count() > 0) {
            log.info("Pricing already initialized, skipping...");
            return;
        }

        log.info("Initializing default pricing...");

        // Default prices in kobo (1 NGN = 100 kobo)
        Map<String, Map<String, Long>> defaultPrices = Map.of(
                "BASIC", Map.of(
                        "MONTHLY", 25000L,
                        "QUARTERLY", 71250L,
                        "ANNUALLY", 270000L
                ),
                "PROFESSIONAL", Map.of(
                        "MONTHLY", 75000L,
                        "QUARTERLY", 213750L,
                        "ANNUALLY", 810000L
                ),
                "ENTERPRISE", Map.of(
                        "MONTHLY", 200000L,
                        "QUARTERLY", 570000L,
                        "ANNUALLY", 2160000L
                ),
                "FREE_TRIAL", Map.of(
                        "MONTHLY", 0L,
                        "QUARTERLY", 0L,
                        "ANNUALLY", 0L
                )
        );

        for (Map.Entry<String, Map<String, Long>> planEntry : defaultPrices.entrySet()) {
            String planName = planEntry.getKey();
            for (Map.Entry<String, Long> periodEntry : planEntry.getValue().entrySet()) {
                PricingConfig config = PricingConfig.builder()
                        .planName(planName)
                        .period(periodEntry.getKey())
                        .priceKobo(periodEntry.getValue())
                        .isActive(true)
                        .build();
                pricingConfigRepository.save(config);
            }
        }

        log.info("Default pricing initialized successfully");
    }

    @Transactional(readOnly = true)
    public Long getPrice(String planName, String period) {
        return pricingConfigRepository.findByPlanNameAndPeriod(planName, period)
                .map(PricingConfig::getPriceKobo)
                .orElse(0L);
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, Long>> getAllPrices() {
        List<PricingConfig> configs = pricingConfigRepository.findByIsActiveTrue();
        Map<String, Map<String, Long>> prices = new HashMap<>();

        for (PricingConfig config : configs) {
            prices.computeIfAbsent(config.getPlanName(), k -> new HashMap<>())
                    .put(config.getPeriod(), config.getPriceKobo());
        }

        return prices;
    }

    @Transactional
    public void updatePrice(String planName, String period, Long priceKobo) {
        pricingConfigRepository.updatePrice(planName, period, priceKobo);
        log.info("Updated price for {} - {}: {} kobo", planName, period, priceKobo);
    }

    @Transactional
    public void updateAllPrices(Map<String, Map<String, Long>> prices) {
        for (Map.Entry<String, Map<String, Long>> planEntry : prices.entrySet()) {
            String planName = planEntry.getKey();
            for (Map.Entry<String, Long> periodEntry : planEntry.getValue().entrySet()) {
                updatePrice(planName, periodEntry.getKey(), periodEntry.getValue());
            }
        }
        log.info("All prices updated successfully");
    }
}