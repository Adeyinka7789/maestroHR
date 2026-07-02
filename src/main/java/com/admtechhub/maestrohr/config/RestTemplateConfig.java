package com.admtechhub.maestrohr.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Configure connection pool
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);  // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(20);  // Maximum connections per route (Paystack API)

        // Configure connection timeouts using Apache HttpClient 5's Timeout
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(5, TimeUnit.SECONDS))     // Connection handshake timeout
                .setSocketTimeout(Timeout.of(10, TimeUnit.SECONDS))      // Socket read timeout
                .build();

        connectionManager.setDefaultConnectionConfig(connectionConfig);

        // Configure request-level timeouts using Apache HttpClient 5's Timeout
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(500, TimeUnit.MILLISECONDS))  // Wait for connection from pool
                .build();

        // Build HTTP client with connection pool and timeouts
        HttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()  // Clean up expired connections
                .evictIdleConnections(TimeValue.of(60, TimeUnit.SECONDS))  // Clean idle connections after 60s
                .build();

        // Create request factory with the configured HTTP client
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        return builder
                .requestFactory(() -> requestFactory)
                .build();
    }
}