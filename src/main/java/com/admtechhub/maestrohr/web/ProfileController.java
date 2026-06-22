package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Self-service profile page (Option 3 — server-rendered HTMX fragments) at {@code /htmx/profile},
 * available to every authenticated role (it falls under {@code anyRequest().authenticated()} in
 * SecurityConfig — only {@code /htmx/admin/**} and {@code /htmx/subscribers/**} are role-gated).
 *
 * <p>All three handlers act only on the caller's own record: {@link ProfileService} and
 * {@link AuthService#changePassword} resolve the subject from the security context, never from a
 * request param, so there is no IDOR surface. SUPER_ADMIN (no Employee record) sees only the
 * Account + Security sections, driven by {@code view.hasEmployee} in the template.
 *
 * <p>Writes re-render only their own section so HTMX swaps the result in place. Validation failures
 * return HTTP 200 with an in-place banner (HTMX skips swaps on 4xx/5xx), mirroring the leave /
 * attendance self-service controllers.
 */
@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final AuthService authService;

    /** Full page: app shell on a cold visit, the populated profile under HTMX. */
    @GetMapping("/htmx/profile")
    public String profile(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("view", profileService.currentProfile());
        return "profile :: content";
    }

    /** Update the editable personal fields, then re-render the personal section with a banner. */
    @PostMapping("/htmx/profile/personal")
    public String updatePersonal(
            @RequestParam String firstName,
            @RequestParam String lastName,
            @RequestParam String phone,
            @RequestParam String address,
            @RequestParam(required = false) String dateOfBirth,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String maritalStatus,
            Model model) {

        try {
            profileService.updatePersonalInfo(firstName, lastName, phone, address,
                    dateOfBirth, gender, maritalStatus);
            model.addAttribute("success", "Your profile has been updated.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
        }

        model.addAttribute("view", profileService.currentProfile());
        return "profile :: personal-section";
    }

    /**
     * Change the caller's password, then re-render the security section with a banner. Match and
     * length checks live here (presentation concern); the current-password verification lives in
     * {@link AuthService#changePassword}. On success the form re-renders empty.
     */
    @PostMapping("/htmx/profile/password")
    public String changePassword(
            @RequestParam(required = false) String currentPassword,
            @RequestParam(required = false) String newPassword,
            @RequestParam(required = false) String confirmPassword,
            Model model) {

        try {
            if (currentPassword == null || currentPassword.isBlank()) {
                throw new IllegalArgumentException("Current password is required.");
            }
            if (newPassword == null || newPassword.length() < 8) {
                throw new IllegalArgumentException("New password must be at least 8 characters.");
            }
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("New password and confirmation do not match.");
            }
            authService.changePassword(currentEmail(), currentPassword, newPassword);
            model.addAttribute("passwordSuccess", "Your password has been changed.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("passwordError", ex.getMessage());
        }

        return "profile :: security-section";
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
