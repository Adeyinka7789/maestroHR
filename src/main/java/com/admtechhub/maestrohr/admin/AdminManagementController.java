package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
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

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminManagementController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/tenants")
    public ResponseEntity<ApiResponse<List<Tenant>>> tenants() {
        return ResponseEntity.ok(ApiResponse.success("Tenants retrieved", tenantRepository.findAllByOrderByCreatedAtDesc()));
    }

    @PostMapping("/tenants")
    public ResponseEntity<ApiResponse<Tenant>> createTenant(@RequestBody TenantRequest request) {
        if (request.getRcNumber() != null && !request.getRcNumber().isBlank() && tenantRepository.existsByRcNumber(request.getRcNumber())) {
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tenant created", tenantRepository.save(tenant)));
    }

    @PutMapping("/tenants/{id}")
    public ResponseEntity<ApiResponse<Tenant>> updateTenant(@PathVariable UUID id, @RequestBody TenantRequest request) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        tenant.setCompanyName(request.getCompanyName());
        tenant.setRcNumber(blankToNull(request.getRcNumber()));
        tenant.setIndustry(request.getIndustry());
        tenant.setCompanySize(request.getCompanySize());
        tenant.setSubscriptionPlan(request.getSubscriptionPlan());
        tenant.setSubscriptionExpiresAt(request.getSubscriptionExpiresAt());
        tenant.setActive(request.isActive());
        return ResponseEntity.ok(ApiResponse.success("Tenant updated", tenantRepository.save(tenant)));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<User>>> users() {
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", userRepository.findAll()));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody UserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }
        Tenant tenant = tenantRepository.findById(request.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.getTenantId()));
        User user = User.builder()
                .tenantId(tenant.getId())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword() != null && !request.getPassword().isBlank() ? request.getPassword() : "ChangeMe123!"))
                .role(request.getRole() != null ? request.getRole() : UserRole.HR_ADMIN)
                .isActive(request.isActive())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created", userRepository.save(user)));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<User>> updateUser(@PathVariable UUID id, @RequestBody UserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        if (request.getTenantId() != null) {
            tenantRepository.findById(request.getTenantId())
                    .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.getTenantId()));
            user.setTenantId(request.getTenantId());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            user.setEmail(request.getEmail());
        }
        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        user.setActive(request.isActive());
        if (request.isUnlockAccount()) {
            user.resetFailedAttempts();
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        return ResponseEntity.ok(ApiResponse.success("User updated", userRepository.save(user)));
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
