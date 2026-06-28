package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tenant user management — invite, re-role, and deactivate/reactivate users within the
 * current tenant. Gated to SYSTEM_ADMIN; SUPER_ADMIN inherits via the role hierarchy.
 * All reads and writes go through the RLS-enforced JPA path: the caller's tenant session
 * is bound in JwtAuthFilter, so UserRepository queries are automatically tenant-scoped.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UsersController {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm", Locale.ENGLISH);

    private static final List<UserRole> ASSIGNABLE_ROLES =
            List.of(UserRole.HR_ADMIN, UserRole.FINANCE_OFFICER, UserRole.DEPT_MANAGER);

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;

    record UserRow(UUID id, String email, UserRole role, String roleDisplay,
                   String roleBadgeStyle, boolean active, String lastLogin, boolean self) {}

    @GetMapping("/htmx/users")
    public String users(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Authentication auth, Model model) {
        if (htmx == null) return "forward:/layout.html";
        populateModel(model, auth.getName());
        return "users :: content";
    }

    @PostMapping("/htmx/users/invite")
    public String invite(
            @RequestParam(defaultValue = "") String email,
            @RequestParam(required = false) UserRole role,
            Authentication auth, Model model) {

        String addr = email.trim().toLowerCase(Locale.ENGLISH);
        if (addr.isBlank() || !addr.contains("@")) {
            model.addAttribute("error", "A valid email address is required.");
            populateModel(model, auth.getName());
            return "users :: content";
        }
        if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
            model.addAttribute("error", "Please select a valid role.");
            populateModel(model, auth.getName());
            return "users :: content";
        }

        UUID tenantId = currentTenantId();
        if (userRepository.findByEmailAndTenantId(addr, tenantId).isPresent()) {
            model.addAttribute("error", "A user with that email already exists.");
            populateModel(model, auth.getName());
            return "users :: content";
        }

        String tempPassword = generateTempPassword();
        User user = User.builder()
                .tenantId(tenantId)
                .email(addr)
                .passwordHash(passwordEncoder.encode(tempPassword))
                .role(role)
                .isActive(true)
                .build();
        userRepository.save(user);
        notificationService.sendUserInviteEmail(addr, tempPassword);

        model.addAttribute("success", "Invitation sent to " + addr + ".");
        populateModel(model, auth.getName());
        return "users :: content";
    }

    @PostMapping("/htmx/users/{id}/role")
    public String changeRole(
            @PathVariable UUID id,
            @RequestParam(required = false) UserRole role,
            Authentication auth, Model model) {

        if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
            model.addAttribute("error", "Please select a valid role.");
            populateModel(model, auth.getName());
            return "users :: content";
        }
        return applyToUser(id, auth, model, user -> {
            if (selfEmail(auth).equals(user.getEmail())) return "You cannot change your own role.";
            if (user.getRole() == UserRole.SUPER_ADMIN || user.getRole() == UserRole.SYSTEM_ADMIN)
                return "This user's role cannot be changed.";
            user.setRole(role);
            userRepository.save(user);
            return null;
        }, "Role updated.");
    }

    @PostMapping("/htmx/users/{id}/deactivate")
    public String deactivate(@PathVariable UUID id, Authentication auth, Model model) {
        return applyToUser(id, auth, model, user -> {
            if (selfEmail(auth).equals(user.getEmail())) return "You cannot deactivate your own account.";
            if (user.getRole() == UserRole.SUPER_ADMIN) return "This user cannot be deactivated.";
            if (!user.isActive()) return "User is already inactive.";
            user.setActive(false);
            userRepository.save(user);
            return null;
        }, "User deactivated.");
    }

    @PostMapping("/htmx/users/{id}/reactivate")
    public String reactivate(@PathVariable UUID id, Authentication auth, Model model) {
        return applyToUser(id, auth, model, user -> {
            if (selfEmail(auth).equals(user.getEmail())) return "You cannot reactivate your own account.";
            if (user.isActive()) return "User is already active.";
            user.setActive(true);
            userRepository.save(user);
            return null;
        }, "User reactivated.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String applyToUser(UUID id, Authentication auth, Model model,
                               java.util.function.Function<User, String> action, String successMsg) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            model.addAttribute("error", "User not found.");
            populateModel(model, auth.getName());
            return "users :: content";
        }
        String err = action.apply(user);
        if (err != null) {
            model.addAttribute("error", err);
        } else {
            model.addAttribute("success", successMsg);
        }
        populateModel(model, auth.getName());
        return "users :: content";
    }

    private void populateModel(Model model, String selfEmail) {
        List<User> users = userRepository.findByTenantIdOrderByCreatedAtDesc(currentTenantId());
        List<UserRow> rows = users.stream().map(u -> toRow(u, selfEmail)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("assignableRoles", ASSIGNABLE_ROLES);
    }

    private UserRow toRow(User u, String selfEmail) {
        String lastLogin = u.getLastLoginAt() != null
                ? u.getLastLoginAt().format(DATE_FORMAT) : "Never";
        return new UserRow(
                u.getId(), u.getEmail(), u.getRole(),
                humanize(u.getRole()), roleBadgeStyle(u.getRole()),
                u.isActive(), lastLogin,
                u.getEmail().equalsIgnoreCase(selfEmail));
    }

    private UUID currentTenantId() {
        return UUID.fromString(TenantContext.getCurrentTenant());
    }

    private String selfEmail(Authentication auth) {
        return auth.getName().toLowerCase(Locale.ENGLISH);
    }

    private String humanize(UserRole role) {
        String s = role.name().replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String roleBadgeStyle(UserRole role) {
        return switch (role) {
            case SUPER_ADMIN, SYSTEM_ADMIN -> "background:#ede9fe;color:#5b21b6";
            case HR_ADMIN                  -> "background:#dbeafe;color:#1d4ed8";
            case FINANCE_OFFICER           -> "background:#dcfce7;color:#15803d";
            case DEPT_MANAGER              -> "background:#ffedd5;color:#c2410c";
            default                        -> "background:#f1f5f9;color:#475569";
        };
    }

    private String generateTempPassword() {
        String upper   = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower   = "abcdefghjkmnpqrstuvwxyz";
        String digits  = "23456789";
        String all     = upper + lower + digits;
        SecureRandom rng = new SecureRandom();
        char[] pw = new char[10];
        pw[0] = upper.charAt(rng.nextInt(upper.length()));
        pw[1] = digits.charAt(rng.nextInt(digits.length()));
        for (int i = 2; i < pw.length; i++) {
            pw[i] = all.charAt(rng.nextInt(all.length()));
        }
        return new String(pw);
    }
}
