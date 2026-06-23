package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered HTMX fragment controller for the super-admin audit log page.
 * Same Option-3 pattern as AdminBillingController / AdminAnalyticsController.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAuditWebController {

    private final AdminAuditService service;

    @GetMapping("/htmx/admin/audit")
    public String audit(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        var pageData = service.buildPage(tenantId, actor, action, entityType,
                statusGroup, dateFrom, dateTo, page, size);

        model.addAttribute("view", pageData);
        // Keep current filter values for form pre-fill
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("actor", actor);
        model.addAttribute("action", action);
        model.addAttribute("entityType", entityType);
        model.addAttribute("statusGroup", statusGroup);
        model.addAttribute("dateFrom", dateFrom);
        model.addAttribute("dateTo", dateTo);

        return "admin-audit :: content";
    }
}