package com.admtechhub.maestrohr.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
public class TenantApiController {

    @GetMapping("/billing-history")
    public ResponseEntity<?> getBillingHistory() {
        // Return empty list (implement real billing logic later if needed)
        return ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList()));
    }
}