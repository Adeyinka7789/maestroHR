package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {

    private final TenantService tenantService;
    private final UserRepository userRepository;

    @GetMapping("/stats")
    public ResponseEntity<?> getAdminStats() {
        long totalTenants = tenantService.countAll();
        long activeTenants = tenantService.countActive();
        long totalUsers = userRepository.count();
        long lockedUsers = userRepository.countLockedUsers();

        Map<String, Object> stats = Map.of(
                "tenants", totalTenants,
                "activeTenants", activeTenants,
                "users", totalUsers,
                "lockedUsers", lockedUsers
        );
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }
}