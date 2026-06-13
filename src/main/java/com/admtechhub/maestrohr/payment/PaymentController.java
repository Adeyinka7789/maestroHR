package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.dto.PlanResponse;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final PricingService pricingService;
    private final PaystackClient paystackClient;
    private final InvoiceRepository invoiceRepository;

    @Value("${paystack.transaction-callback-url:http://localhost:8080/dashboard?payment=success}")
    private String transactionCallbackUrl;

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

    @PostMapping("/initialize")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> initializePayment(
            @RequestBody PaymentInitializeRequest request,
            Authentication authentication) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        SubscriptionPlan plan = SubscriptionPlan.valueOf(request.getPlan().toUpperCase());
        PaymentPeriod period = PaymentPeriod.valueOf(request.getPeriod().toUpperCase());

        // Resolve the price server-side from pricing_config (kobo). The client-supplied
        // amount is intentionally ignored — trusting it would let a caller underpay.
        long amountKobo = pricingService.getPrice(plan.name(), period.name());
        if (amountKobo <= 0) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Plan " + plan + "/" + period + " is not payable"));
        }

        // Bill the authenticated user's email (JWT principal). Paystack requires a real,
        // unique email per customer — the old companyName@maestrohr.com placeholder was fake.
        String billingEmail = authentication.getName();
        String reference = "SUB_" + System.currentTimeMillis() + "_" + tenantId.toString().substring(0, 8);

        PaystackResponse.Data init = paystackClient.initializeTransaction(
                billingEmail, amountKobo, reference, transactionCallbackUrl);

        // Record the attempt as PENDING; the charge.success webhook flips it to SUCCESS.
        invoiceRepository.save(Invoice.builder()
                .tenant(tenant)
                .paystackReference(reference)
                .amountKobo(amountKobo)
                .status(PaymentStatus.PENDING)
                .plan(plan)
                .period(period)
                .build());

        Map<String, String> response = Map.of(
                "authorization_url", init.getAuthorizationUrl(),
                "reference", reference
        );

        log.info("Initialized Paystack transaction {} for tenant {} ({} {}, {} kobo)",
                reference, tenantId, plan, period, amountKobo);

        return ResponseEntity.ok(ApiResponse.success("Payment initialized", response));
    }
}