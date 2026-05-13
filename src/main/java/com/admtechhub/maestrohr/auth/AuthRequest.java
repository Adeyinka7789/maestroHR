package com.admtechhub.maestrohr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

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
    }
}