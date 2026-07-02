package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.dto.PlanResponse;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Payment REST controller.
 *
 * Delegates all transactional orchestration and external API calls
 * to {@link PaymentService} to keep the controller thin and testable.
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaystackOAuthService paystackOAuthService;
    private final SubscriptionService subscriptionService;
    private final PricingService pricingService;
    private final PaymentService paymentService;

    private UUID getCurrentTenantId() {
        return UUID.fromString(TenantContext.getCurrentTenant());
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> getPlans() {
        List<PlanResponse> plans = Arrays.stream(SubscriptionPlan.values())
                .map(plan -> PlanResponse.from(
                        plan,
                        pricingService.getPrice(plan.name(), PaymentPeriod.MONTHLY.name()),
                        pricingService.getPrice(plan.name(), PaymentPeriod.QUARTERLY.name()),
                        pricingService.getPrice(plan.name(), PaymentPeriod.ANNUALLY.name())))
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

    /**
     * Initialize a Paystack payment for subscription.
     *
     * Delegates to {@link PaymentService} which handles:
     * 1. Price validation (server-side, never trusts client)
     * 2. Idempotent reference generation
     * 3. PENDING invoice persistence BEFORE Paystack call
     * 4. Paystack API call OUTSIDE transaction
     * 5. Failure marking if Paystack call fails
     */
    @PostMapping("/initialize")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> initializePayment(
            @RequestBody PaymentInitializeRequest request,
            Authentication authentication) {

        UUID tenantId = getCurrentTenantId();
        String billingEmail = authentication.getName();

        try {
            Map<String, String> initDetails = paymentService.prepareAndInitializePayment(
                    tenantId, billingEmail, request.getPlan(), request.getPeriod());

            return ResponseEntity.ok(ApiResponse.success("Payment initialized", initDetails));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Payment initialization failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Payment initialization failed. Please try again."));
        }
    }
}