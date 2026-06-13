package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SUPER_ADMIN tenant/user management. Every operation here spans tenants other than the admin's
 * own, so both the reads ({@link AdminStatsQueries}, {@link AuthBootstrapQueries}) and the writes
 * ({@link TenantUserWrites}) go through the privileged datasource — the scoped JPA path would be
 * collapsed to (or rejected against) the admin's own tenant by RLS under {@code maestro_app}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminManagementController {

    private final AdminStatsQueries adminStatsQueries;
    private final AuthBootstrapQueries authBootstrapQueries;
    private final TenantUserWrites tenantUserWrites;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/tenants")
    public ResponseEntity<ApiResponse<List<TenantWithUserCountDTO>>> tenants() {
        List<TenantWithUserCountDTO> tenants = adminStatsQueries.findAllTenantsWithUserCount();
        return ResponseEntity.ok(ApiResponse.success("Tenants retrieved", tenants));
    }

    @PostMapping("/tenants")
    public ResponseEntity<ApiResponse<Tenant>> createTenant(@RequestBody TenantRequest request) {
        if (request.getRcNumber() != null && !request.getRcNumber().isBlank()
                && authBootstrapQueries.existsTenantByRcNumber(request.getRcNumber())) {
            throw new IllegalArgumentException("A tenant with this RC number already exists");
        }
        Tenant tenant = Tenant.builder()
                .companyName(request.getCompanyName())
                .rcNumber(blankToNull(request.getRcNumber()))
                .industry(request.getIndustry())
                .companySize(request.getCompanySize())
                .subscriptionPlan(request.getSubscriptionPlan() != null ? request.getSubscriptionPlan() : SubscriptionPlan.FREE_TRIAL)
                .subscriptionExpiresAt(request.getSubscriptionExpiresAt() != null ? request.getSubscriptionExpiresAt() : OffsetDateTime.now().plusDays(30))
                .isActive(request.isActive())
                .build();
        tenantUserWrites.insertTenant(tenant);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tenant created", tenant));
    }

    @PutMapping("/tenants/{id}")
    public ResponseEntity<ApiResponse<Tenant>> updateTenant(@PathVariable UUID id, @RequestBody TenantRequest request) {
        Tenant tenant = Tenant.builder()
                .companyName(request.getCompanyName())
                .rcNumber(blankToNull(request.getRcNumber()))
                .industry(request.getIndustry())
                .companySize(request.getCompanySize())
                .subscriptionPlan(request.getSubscriptionPlan())
                .subscriptionExpiresAt(request.getSubscriptionExpiresAt())
                .isActive(request.isActive())
                .build();
        tenant.setId(id);
        if (!tenantUserWrites.updateTenant(tenant)) {
            throw new IllegalArgumentException("Tenant not found: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success("Tenant updated", tenant));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<AdminStatsQueries.AdminUserRow>>> users() {
        return ResponseEntity.ok(
                ApiResponse.success("Users retrieved", adminStatsQueries.findAllUsers()));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody UserRequest request) {
        if (authBootstrapQueries.existsUserByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }
        if (authBootstrapQueries.findTenantById(request.getTenantId()).isEmpty()) {
            throw new IllegalArgumentException("Tenant not found: " + request.getTenantId());
        }
        User user = User.builder()
                .tenantId(request.getTenantId())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword() != null && !request.getPassword().isBlank() ? request.getPassword() : "ChangeMe123!"))
                .role(request.getRole() != null ? request.getRole() : UserRole.HR_ADMIN)
                .isActive(request.isActive())
                .build();
        tenantUserWrites.insertUser(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created", user));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<User>> updateUser(@PathVariable UUID id, @RequestBody UserRequest request) {
        // Read the current row across tenants (privileged), then merge the partial request over it.
        AuthBootstrapQueries.UserAuthRow current = authBootstrapQueries.findUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        UUID tenantId = current.tenantId();
        if (request.getTenantId() != null) {
            if (authBootstrapQueries.findTenantById(request.getTenantId()).isEmpty()) {
                throw new IllegalArgumentException("Tenant not found: " + request.getTenantId());
            }
            tenantId = request.getTenantId();
        }

        String email = (request.getEmail() != null && !request.getEmail().isBlank())
                ? request.getEmail() : current.email();
        UserRole role = request.getRole() != null
                ? request.getRole() : UserRole.valueOf(current.role());
        int failedAttempts = current.failedLoginAttempts();
        OffsetDateTime lockedUntil = current.lockedUntil();
        if (request.isUnlockAccount()) {
            failedAttempts = 0;
            lockedUntil = null;
        }
        String passwordHash = (request.getPassword() != null && !request.getPassword().isBlank())
                ? passwordEncoder.encode(request.getPassword()) : current.passwordHash();

        User user = User.builder()
                .tenantId(tenantId)
                .email(email)
                .passwordHash(passwordHash)
                .role(role)
                .isActive(request.isActive())
                .failedLoginAttempts(failedAttempts)
                .lockedUntil(lockedUntil)
                .build();
        user.setId(id);
        tenantUserWrites.updateUser(user);
        return ResponseEntity.ok(ApiResponse.success("User updated", user));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Data
    public static class TenantRequest {
        @NotBlank
        private String companyName;
        private String rcNumber;
        @NotBlank
        private String industry;
        @NotBlank
        private String companySize;
        private SubscriptionPlan subscriptionPlan = SubscriptionPlan.FREE_TRIAL;
        private OffsetDateTime subscriptionExpiresAt = OffsetDateTime.now().plusDays(30);
        private boolean active = true;
    }

    @Data
    public static class UserRequest {
        private UUID tenantId;
        @Email
        @NotBlank
        private String email;
        private String password;
        private UserRole role = UserRole.HR_ADMIN;
        private boolean active = true;
        private boolean unlockAccount;
    }
}
