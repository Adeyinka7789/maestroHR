package com.admtechhub.maestrohr.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PaystackToken {
    private boolean status;
    private String message;
    private TokenData data;

    @Data
    public static class TokenData {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("token_type")
        private String tokenType;

        @JsonProperty("expires_in")
        private int expiresIn;

        @JsonProperty("customer_code")
        private String customerCode;

        @JsonProperty("subscription_code")
        private String subscriptionCode;

        @JsonProperty("refresh_token")
        private String refreshToken;
    }
}