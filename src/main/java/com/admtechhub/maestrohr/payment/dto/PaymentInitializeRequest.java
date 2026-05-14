package com.admtechhub.maestrohr.payment.dto;

import lombok.Data;

@Data
public class PaymentInitializeRequest {
    private String plan;
    private String period;
    private Long amount;
}