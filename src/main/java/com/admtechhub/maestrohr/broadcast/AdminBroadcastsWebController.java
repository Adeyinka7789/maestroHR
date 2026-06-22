package com.admtechhub.maestrohr.broadcast;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Server-rendered HTMX fragment controller for the super-admin broadcasts console (Feature 5 —
 * the same Option-3 pattern as {@code AdminBillingController} / {@code SuperAdminDashboardController}).
 *
 * <p>The compose form and the "sent" table both live in {@code admin-broadcasts.html}; the form
 * POSTs to {@code /api/admin/broadcasts} (see {@link AdminBroadcastController}) via an inline fetch,
 * then reloads the fragment. Authorization is enforced at the URL layer
 * ({@code /htmx/admin/**} → SUPER_ADMIN in SecurityConfig); {@code @PreAuthorize} is the backstop.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminBroadcastsWebController {

    private final BroadcastService broadcastService;

    @GetMapping("/htmx/admin/broadcasts")
    public String broadcasts(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("broadcasts", broadcastService.listAllWithReadCounts());
        return "admin-broadcasts :: content";
    }
}
