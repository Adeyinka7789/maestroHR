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
}