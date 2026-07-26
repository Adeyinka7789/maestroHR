package com.admtechhub.maestrohr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "paystack")
public class PaystackConfig {
    private String secretKey;
    private String baseUrl = "https://api.paystack.co";
    private String callbackUrl;

    /** True when a live secret key is configured — real money moves. Drives the payout warning. */
    public boolean isLiveMode() {
        return secretKey != null && secretKey.startsWith("sk_live");
    }

    /** "LIVE", "TEST", or "UNSET" — surfaced on the disbursement page so the operator sees the mode. */
    public String getMode() {
        if (secretKey == null || secretKey.isBlank()) {
            return "UNSET";
        }
        return secretKey.startsWith("sk_live") ? "LIVE" : "TEST";
    }
}