package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.disbursement.provider.PaystackDisbursementProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DisbursementFactory {

    private final PaystackDisbursementProvider paystackProvider;
    private final CSVDisbursementProvider csvProvider;

    private final Map<String, DisbursementProvider> providers = new ConcurrentHashMap<>();

    public DisbursementProvider getProvider(String providerName) {
        if (providers.isEmpty()) {
            providers.put("PAYSTACK", paystackProvider);
            providers.put("CSV", csvProvider);
            // Add more providers as they are implemented
        }

        DisbursementProvider provider = providers.get(providerName.toUpperCase());
        if (provider == null) {
            return providers.get("CSV"); // Default to CSV
        }
        return provider;
    }

    public DisbursementProvider getDefaultProvider() {
        return providers.get("PAYSTACK");
    }

    public void addProvider(String name, DisbursementProvider provider) {
        providers.put(name.toUpperCase(), provider);
    }
}