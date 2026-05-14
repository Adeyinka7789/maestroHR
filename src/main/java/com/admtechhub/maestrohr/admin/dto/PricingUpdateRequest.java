package com.admtechhub.maestrohr.admin.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PricingUpdateRequest {
    private Map<String, Map<String, Long>> prices;
}