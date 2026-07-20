package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.dto.PlanResponse;
import com.admtechhub.maestrohr.tenant.AppliedDiscount;
import com.admtechhub.maestrohr.tenant.DiscountService;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private final DiscountService discountService;

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

    /**
     * Server-side price quote for the current tenant, including any admin-configured discount that
     * applies to them. The checkout page calls this so the displayed total matches exactly what
     * will be charged — the discount is never computed on (or trusted from) the client. Per-customer
     * discounts resolve because this runs under the caller's tenant context.
     */
    @GetMapping("/quote")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quote(
            @RequestParam SubscriptionPlan plan,
            @RequestParam PaymentPeriod period) {
        UUID tenantId = getCurrentTenantId();
        long baseKobo = pricingService.getPrice(plan.name(), period.name());
        AppliedDiscount applied = discountService.resolveBestDiscount(tenantId, plan.name(), period.name(), baseKobo);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baseKobo", applied.baseKobo());
        data.put("discountKobo", applied.discountKobo());
        data.put("netKobo", applied.netKobo());
        data.put("discountLabel", applied.label());
        return ResponseEntity.ok(ApiResponse.success("Quote", data));
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

    /**
     * On-demand reconciliation of the current tenant's PENDING invoices against Paystack.
     *
     * <p>Every "Pay" click persists a PENDING invoice stub before the Paystack call, and a stub
     * lingers as PENDING whenever the browser never returns to {@link #paymentCallback} (checkout
     * abandoned, or — on localhost — the {@code charge.success} webhook cannot reach the app). This
     * lets an admin re-confirm each pending charge directly with Paystack and activate any that were
     * actually paid. Idempotent and safe: unpaid invoices are left untouched.
     */
    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcilePending() {
        PaymentService.ReconcileResult result = paymentService.reconcilePendingInvoices();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checked", result.checked());
        data.put("activated", result.activated());
        data.put("stillPending", result.stillPending());
        data.put("errored", result.errored());

        String message;
        if (result.checked() == 0) {
            message = "No pending payments to reconcile.";
        } else if (result.activated() > 0) {
            message = result.activated() + " payment(s) confirmed and activated.";
        } else {
            message = "Checked " + result.checked() + " pending payment(s); none are confirmed paid yet.";
        }
        return ResponseEntity.ok(ApiResponse.success(message, data));
    }

    /**
     * Return-from-Paystack callback — the browser lands here after the user completes (or
     * abandons) the hosted checkout. Paystack appends {@code ?reference=} (and {@code ?trxref=})
     * to the configured transaction callback URL.
     *
     * <p>This is a top-level browser navigation from an external origin, so it carries no
     * {@code Authorization: Bearer} header — hence the endpoint is <strong>public</strong>
     * (see {@link com.admtechhub.maestrohr.auth.PublicPaths#NO_TENANT}). It does not trust the
     * redirect: {@link PaymentService#verifyAndActivate} confirms the charge with Paystack
     * server-side before activating, and resolves/binds the tenant from the invoice reference.
     * This path is what activates the subscription on localhost, where the inbound
     * {@code charge.success} webhook cannot reach the app; it is idempotent with the webhook.
     *
     * <p>Redirects (302) to the settings page with a {@code ?payment=} status so the UI can
     * surface the outcome and the (now populated) billing history.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> paymentCallback(
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String trxref) {

        String ref = (reference != null && !reference.isBlank()) ? reference : trxref;
        String status = (ref == null || ref.isBlank()) ? "failed" : paymentService.verifyAndActivate(ref);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/htmx/settings?payment=" + status))
                .build();
    }
}