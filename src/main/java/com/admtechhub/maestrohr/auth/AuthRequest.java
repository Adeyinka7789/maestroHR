package com.admtechhub.maestrohr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

public class AuthRequest {

    @Data
    public static class Register {
        @NotBlank(message = "Company name is required")
        private String companyName;

        private String rcNumber;

        @NotBlank(message = "Industry is required")
        private String industry;

        @NotBlank(message = "Company size is required")
        private String companySize;

        @Email(message = "Valid email is required")
        @NotBlank(message = "Email is required")
        private String adminEmail;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;
    }

    @Data
    public static class Login {
        @Email(message = "Valid email is required")
        @NotBlank(message = "Email is required")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;

        /**
         * Set only on the second round-trip of a multi-company login: after a first call
         * returns {@code requiresTenantSelection = true} with the list of companies, the caller
         * resubmits with this set to the chosen tenant.
         */
        private UUID tenantId;
    }

    /** Create an additional company for an already-authenticated user (see AuthService#addCompany). */
    @Data
    public static class AddCompany {
        @NotBlank(message = "Company name is required")
        private String companyName;

        private String rcNumber;

        @NotBlank(message = "Industry is required")
        private String industry;

        @NotBlank(message = "Company size is required")
        private String companySize;
    }

    @Data
    public static class ForgotPassword {
        @Email(message = "Valid email is required")
        @NotBlank(message = "Email is required")
        private String email;
    }

    @Data
    public static class ResetPassword {
        @NotBlank(message = "Token is required")
        private String token;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String newPassword;
    }
}