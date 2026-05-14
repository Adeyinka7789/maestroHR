package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.payment.dto.PaystackToken;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaystackOAuthService {

    private final TenantRepository tenantRepository;
    private final RestTemplate restTemplate;

    @Value("${paystack.client-id:}")
    private String clientId;

    @Value("${paystack.client-secret:}")
    private String clientSecret;

    @Value("${paystack.oauth-callback-url:http://localhost:8080/api/payment/oauth/callback}")
    private String callbackUrl;

    /**
     * Generate OAuth URL for tenant to connect their Paystack account
     */
    public String getAuthorizationUrl(UUID tenantId) {
        return UriComponentsBuilder.fromHttpUrl("https://api.paystack.co/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("state", tenantId.toString())
                .queryParam("scope", "transfers")
                .build()
                .toUriString();
    }

    /**
     * Exchange authorization code for access token (no keys stored)
     */
    public PaystackToken exchangeCodeForToken(String code, UUID tenantId) {
        String url = "https://api.paystack.co/oauth/token";

        Map<String, String> request = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", callbackUrl
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<PaystackToken> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, PaystackToken.class
            );

            // Store only the paystack_customer_code, NOT the access token
            if (response.getBody() != null && response.getBody().isStatus() && response.getBody().getData() != null) {
                Tenant tenant = tenantRepository.findById(tenantId)
                        .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

                tenant.setPaystackCustomerCode(response.getBody().getData().getCustomerCode());
                if (response.getBody().getData().getSubscriptionCode() != null) {
                    tenant.setPaystackSubscriptionCode(response.getBody().getData().getSubscriptionCode());
                }
                tenantRepository.save(tenant);

                log.info("Paystack OAuth completed for tenant {}", tenantId);
            }
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to exchange OAuth code: {}", e.getMessage());
            throw new RuntimeException("Failed to connect Paystack account", e);
        }
    }

    /**
     * Check if tenant has connected Paystack
     */
    public boolean isPaystackConnected(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        return tenant.getPaystackCustomerCode() != null;
    }
}