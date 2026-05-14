package com.admtechhub.maestrohr.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class PaystackWebhookPayload {
    private String event;
    private Data data;

    @lombok.Data
    public static class Data {
        private Long id;

        @JsonProperty("domain")
        private String domain;

        private String status;

        private String reference;

        private Long amount;

        @JsonProperty("amount_paid")
        private Long amountPaid;

        @JsonProperty("paid_at")
        private OffsetDateTime paidAt;

        private Map<String, Object> metadata;

        private Customer customer;

        private Authorization authorization;

        private Plan plan;

        @JsonProperty("subscription_code")
        private String subscriptionCode;

        @JsonProperty("email_token")
        private String emailToken;

        @JsonProperty("created_at")
        private OffsetDateTime createdAt;
    }

    @lombok.Data
    public static class Customer {
        private Long id;

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        private String email;

        @JsonProperty("customer_code")
        private String customerCode;

        private String phone;
    }

    @lombok.Data
    public static class Authorization {
        private String authorization_code;
        private String bin;
        private String last4;
        private String exp_month;
        private String exp_year;
        private String channel;
        private String card_type;
        private String bank;
        private String country_code;
        private String brand;
        private boolean reusable;
        private String signature;
    }

    @lombok.Data
    public static class Plan {
        private Long id;
        private String name;
        @JsonProperty("plan_code")
        private String planCode;
        private String description;
        private Long amount;
        private String interval;
        @JsonProperty("send_invoices")
        private boolean sendInvoices;
        @JsonProperty("send_sms")
        private boolean sendSms;
        private String currency;
    }
}