package com.admtechhub.maestrohr.auth;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String email;
    private String role;
    private UUID tenantId;
    private String companyName;
}