package com.admtechhub.maestrohr.paystack;

import com.admtechhub.maestrohr.config.PaystackConfig;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackApiException;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaystackClientTest {

    @Mock private PaystackConfig paystackConfig;
    @Mock private RestTemplate restTemplate;

    @InjectMocks private PaystackClient paystackClient;

    @Test
    void initializeTransaction_httpError_surfacesStatusAndBody() {
        when(paystackConfig.getBaseUrl()).thenReturn("https://api.paystack.co");
        when(paystackConfig.getSecretKey()).thenReturn("sk_test_valid");
        String body = "{\"status\":false,\"message\":\"Invalid key\"}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(PaystackResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> paystackClient.initializeTransaction("user@x.io", 500000L, "REF_1", "cb"))
                .isInstanceOf(PaystackApiException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid key");
    }

    @Test
    void initializeTransaction_missingSecretKey_failsFastWithClearMessage() {
        lenient().when(paystackConfig.getBaseUrl()).thenReturn("https://api.paystack.co");
        when(paystackConfig.getSecretKey()).thenReturn("  ");

        assertThatThrownBy(() -> paystackClient.initializeTransaction("user@x.io", 500000L, "REF_2", "cb"))
                .isInstanceOf(PaystackApiException.class)
                .hasMessageContaining("PAYSTACK_SECRET_KEY");
    }
}
