package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminApiController {

    // SUPER_ADMIN console aggregates span ALL tenants, so they must go through the
    // privileged datasource — under the RLS-enforced primary role these counts would
    // collapse to the (absent) current tenant.
    private final AdminStatsQueries adminStatsQueries;

    @GetMapping("/stats")
    public ResponseEntity<?> getAdminStats() {
        long totalTenants = adminStatsQueries.countTenants();
        long activeTenants = adminStatsQueries.countActiveTenants();
        long totalUsers = adminStatsQueries.countUsers();
        long lockedUsers = adminStatsQueries.countLockedUsers();

        Map<String, Object> stats = Map.of(
                "tenants", totalTenants,
                "activeTenants", activeTenants,
                "users", totalUsers,
                "lockedUsers", lockedUsers
        );
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }
}