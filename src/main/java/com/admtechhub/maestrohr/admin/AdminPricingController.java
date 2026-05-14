package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.admin.dto.PricingResponse;
import com.admtechhub.maestrohr.admin.dto.PricingUpdateRequest;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.tenant.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AdminPricingController {

    private final PricingService pricingService;

    // ── SUPER_ADMIN: read all prices ──────────────────────────────────────────
    @GetMapping("/api/admin/pricing")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PricingResponse>> getPricing() {
        Map<String, Map<String, Long>> prices = pricingService.getAllPrices();
        PricingResponse response = PricingResponse.builder()
                .prices(prices)
                .lastUpdated(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
        return ResponseEntity.ok(ApiResponse.success("Pricing retrieved", response));
    }

    // ── SUPER_ADMIN: update all prices ────────────────────────────────────────
    @PutMapping("/api/admin/pricing")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updatePricing(@RequestBody PricingUpdateRequest request) {
        if (request.getPrices() == null || request.getPrices().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No pricing data provided"));
        }
        pricingService.updateAllPrices(request.getPrices());
        log.info("Pricing updated by admin");
        return ResponseEntity.ok(ApiResponse.success("Pricing updated successfully", null));
    }

    // ── SUPER_ADMIN: update single price ──────────────────────────────────────
    @PutMapping("/api/admin/pricing/{planName}/{period}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateSinglePrice(
            @PathVariable String planName,
            @PathVariable String period,
            @RequestParam Long price) {
        pricingService.updatePrice(planName.toUpperCase(), period.toUpperCase(), price);
        return ResponseEntity.ok(ApiResponse.success("Price updated successfully", null));
    }

    // ── PUBLIC: no auth required — used by the pricing page ──────────────────
    // Clean URL: /api/pricing/public
    @GetMapping("/api/pricing/public")
    public ResponseEntity<ApiResponse<PricingResponse>> getPublicPricing() {
        Map<String, Map<String, Long>> prices = pricingService.getAllPrices();
        PricingResponse response = PricingResponse.builder()
                .prices(prices)
                .lastUpdated(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
        return ResponseEntity.ok(ApiResponse.success("Pricing retrieved", response));
    }
}