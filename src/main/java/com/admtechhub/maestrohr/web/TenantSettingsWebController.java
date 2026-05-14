package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class TenantSettingsWebController {

    private final TenantService tenantService;

    private UUID getCurrentTenantId() {
        // In production, get from authenticated user
        return UUID.fromString("0acb1bbb-70a1-4b06-8f1d-331dc620e19a");
    }

    @GetMapping
    public String settings(Model model) {
        Tenant tenant = tenantService.findById(getCurrentTenantId());

        model.addAttribute("pageTitle", "Company Settings");
        model.addAttribute("tenant", tenant);
        model.addAttribute("subscriptionPlans", SubscriptionPlan.values());
        model.addAttribute("disbursementProviders", new String[]{"PAYSTACK", "CSV"});

        return "settings/index";
    }
}