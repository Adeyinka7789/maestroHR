package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.PaystackOAuthService;
import com.admtechhub.maestrohr.tenant.dto.TenantSettingsRequest;
import com.admtechhub.maestrohr.tenant.dto.TenantSettingsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/tenant/settings")
@RequiredArgsConstructor
@Slf4j
public class TenantSettingsController {

    private final TenantService tenantService;
    private final TenantRepository tenantRepository;
    private final PaystackOAuthService paystackOAuthService;

    private UUID getCurrentTenantId() {
        // Get from SecurityContext - you may need to adjust this
        // For now, get the first tenant (simplified for demo)
        return tenantRepository.findAll().get(0).getId();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> getSettings() {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantService.findById(tenantId);

        TenantSettingsResponse response = TenantSettingsResponse.builder()
                .companyName(tenant.getCompanyName())
                .industry(tenant.getIndustry())
                .companySize(tenant.getCompanySize())
                .subscriptionPlan(tenant.getSubscriptionPlan())
                .paymentPeriod(tenant.getPaymentPeriod())
                .subscriptionExpiresAt(tenant.getSubscriptionExpiresAt())
                .disbursementProvider(tenant.getDisbursementProvider())
                .autoRenew(tenant.isAutoRenew())
                .isActive(tenant.isActive())
                .paystackConnected(tenant.getPaystackCustomerCode() != null)
                .build();

        return ResponseEntity.ok(ApiResponse.success("Settings retrieved", response));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> updateSettings(
            @RequestBody TenantSettingsRequest request) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantService.findById(tenantId);

        // Update allowed fields
        if (request.getDisbursementProvider() != null) {
            tenant.setDisbursementProvider(request.getDisbursementProvider());
        }
        if (request.getAutoRenew() != null) {
            tenant.setAutoRenew(request.getAutoRenew());
        }
        if (request.getCompanyName() != null) {
            tenant.setCompanyName(request.getCompanyName());
        }
        if (request.getIndustry() != null) {
            tenant.setIndustry(request.getIndustry());
        }
        if (request.getCompanySize() != null) {
            tenant.setCompanySize(request.getCompanySize());
        }

        Tenant saved = tenantRepository.save(tenant);

        TenantSettingsResponse response = TenantSettingsResponse.builder()
                .companyName(saved.getCompanyName())
                .industry(saved.getIndustry())
                .companySize(saved.getCompanySize())
                .subscriptionPlan(saved.getSubscriptionPlan())
                .paymentPeriod(saved.getPaymentPeriod())
                .subscriptionExpiresAt(saved.getSubscriptionExpiresAt())
                .disbursementProvider(saved.getDisbursementProvider())
                .autoRenew(saved.isAutoRenew())
                .isActive(saved.isActive())
                .paystackConnected(saved.getPaystackCustomerCode() != null)
                .build();

        return ResponseEntity.ok(ApiResponse.success("Settings updated", response));
    }

    @PostMapping("/disbursement-provider")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Tenant>> setDisbursementProvider(
            @RequestParam String provider) {
        UUID tenantId = getCurrentTenantId();

        if (!provider.equals("PAYSTACK") && !provider.equals("CSV")) {
            throw new IllegalArgumentException("Invalid provider. Supported: PAYSTACK, CSV");
        }

        Tenant tenant = tenantService.findById(tenantId);
        tenant.setDisbursementProvider(provider);
        Tenant saved = tenantRepository.save(tenant);

        return ResponseEntity.ok(ApiResponse.success("Disbursement provider updated to " + provider, saved));
    }

    @GetMapping("/paystack/connect-url")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<String>> getPaystackConnectUrl() {
        UUID tenantId = getCurrentTenantId();
        String url = paystackOAuthService.getAuthorizationUrl(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Paystack OAuth URL", url));
    }

    @GetMapping("/paystack/status")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> getPaystackStatus() {
        UUID tenantId = getCurrentTenantId();
        boolean connected = paystackOAuthService.isPaystackConnected(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Paystack connection status", connected));
    }
}