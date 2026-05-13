package com.admtechhub.maestrohr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "termii")
public class TermiiConfig {
    private String apiKey;
    private String senderId;
    private String baseUrl;
}