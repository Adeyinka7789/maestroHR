package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.dto.PlanResponse;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaystackOAuthService paystackOAuthService;
    private final SubscriptionService subscriptionService;
    private final TenantRepository tenantRepository;

    private UUID getCurrentTenantId() {
        // Get from SecurityContext or TenantContext
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        // In production, get tenant from user
        return tenantRepository.findAll().get(0).getId();
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> getPlans() {
        List<PlanResponse> plans = Arrays.stream(SubscriptionPlan.values())
                .map(PlanResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Plans retrieved", plans));
    }

    @PostMapping("/connect-paystack")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<ApiResponse<String>> connectPaystack() {
        UUID tenantId = getCurrentTenantId();
        String authUrl = paystackOAuthService.getAuthorizationUrl(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Paystack OAuth URL", authUrl));
    }

    @GetMapping("/oauth/callback")
    public String oauthCallback(@RequestParam String code, @RequestParam String state) {
        UUID tenantId = UUID.fromString(state);
        paystackOAuthService.exchangeCodeForToken(code, tenantId);
        return "redirect:/dashboard?payment=success";
    }

    @PostMapping("/upgrade")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Tenant>> upgradePlan(
            @RequestParam SubscriptionPlan plan,
            @RequestParam PaymentPeriod period) {
        UUID tenantId = getCurrentTenantId();
        Tenant upgraded = subscriptionService.upgradePlan(tenantId, plan, period);
        return ResponseEntity.ok(ApiResponse.success("Plan upgraded successfully", upgraded));
    }

    @PostMapping("/admin/upgrade/{tenantId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Tenant>> adminUpgradePlan(
            @PathVariable UUID tenantId,
            @RequestParam SubscriptionPlan plan,
            @RequestParam PaymentPeriod period) {
        Tenant upgraded = subscriptionService.upgradePlan(tenantId, plan, period);
        return ResponseEntity.ok(ApiResponse.success("Tenant upgraded successfully", upgraded));
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> cancelSubscription() {
        UUID tenantId = getCurrentTenantId();
        subscriptionService.cancelSubscription(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Subscription cancelled", null));
    }

    @PostMapping("/initialize")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> initializePayment(
            @RequestBody PaymentInitializeRequest request) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        // Initialize Paystack transaction
        String callbackUrl = "http://localhost:8080/api/payment/verify";
        String reference = "SUB_" + System.currentTimeMillis() + "_" + tenantId.toString().substring(0, 8);

        Map<String, String> response = Map.of(
                "authorization_url", String.format(
                        "https://api.paystack.co/transaction/initialize?reference=%s&amount=%d&email=%s&callback_url=%s",
                        reference, request.getAmount(), tenant.getCompanyName() + "@maestrohr.com", callbackUrl
                ),
                "reference", reference
        );

        // Store pending upgrade in cache or database
        // For now, just return URL

        return ResponseEntity.ok(ApiResponse.success("Payment initialized", response));
    }
}