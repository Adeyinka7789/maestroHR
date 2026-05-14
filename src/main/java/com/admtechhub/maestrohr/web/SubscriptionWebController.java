package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/subscription")
@RequiredArgsConstructor
public class SubscriptionWebController {

    private final TenantService tenantService;

    private UUID getCurrentTenantId() {
        return UUID.fromString("0acb1bbb-70a1-4b06-8f1d-331dc620e19a");
    }

    @GetMapping("/plans")
    public String viewPlans(Model model) {
        Tenant tenant = tenantService.findById(getCurrentTenantId());

        // Build priceMap: { "FREE_TRIAL": { "MONTHLY": 0, "QUARTERLY": 0, "ANNUALLY": 0 }, ... }
        // Values in NAIRA (divided by 100 from kobo).
        // Thymeleaf writes these into data-* attributes — no fetch needed in the browser.
        Map<String, Map<String, Long>> priceMap = new LinkedHashMap<>();
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            Map<String, Long> periods = new LinkedHashMap<>();
            periods.put("MONTHLY",   plan.getPriceKobo()           / 100L);
            periods.put("QUARTERLY", plan.getQuarterlyPriceKobo()  / 100L);
            periods.put("ANNUALLY",  plan.getAnnualPriceKobo()     / 100L);
            priceMap.put(plan.name(), periods);
        }

        model.addAttribute("pageTitle",    "Subscription Plans");
        model.addAttribute("currentPlan",  tenant.getSubscriptionPlan());
        model.addAttribute("plans",        Arrays.asList(SubscriptionPlan.values()));
        model.addAttribute("tenant",       tenant);
        model.addAttribute("priceMap",     priceMap);   // ← new

        return "subscription/plans";
    }

    @GetMapping("/checkout")
    public String checkout(Model model,
                           @RequestParam SubscriptionPlan plan,
                           @RequestParam(required = false, defaultValue = "MONTHLY") String period) {
        Tenant tenant = tenantService.findById(getCurrentTenantId());

        model.addAttribute("pageTitle",     "Checkout");
        model.addAttribute("selectedPlan",  plan);
        model.addAttribute("period",        period);
        model.addAttribute("tenant",        tenant);
        model.addAttribute("amount",        getAmountForPlan(plan, period));

        return "subscription/checkout";
    }

    private long getAmountForPlan(SubscriptionPlan plan, String period) {
        return switch (period.toUpperCase()) {
            case "QUARTERLY" -> plan.getQuarterlyPriceKobo();
            case "ANNUALLY"  -> plan.getAnnualPriceKobo();
            default          -> plan.getPriceKobo();
        };
    }
}