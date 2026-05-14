package com.admtechhub.maestrohr.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PricingResponse {
    private Map<String, Map<String, Long>> prices;
    private String lastUpdated;
}