package com.admtechhub.maestrohr.auth;

import lombok.Builder;
import lombok.Data;

import java.util.List;
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

    /**
     * True when the email/password matched more than one company membership and the caller must
     * pick one before a token can be issued. When true, every field above is null and {@link
     * #tenants} carries the choices; the caller resubmits {@code POST /api/auth/login} with
     * {@code tenantId} set to the chosen one.
     */
    @Builder.Default
    private boolean requiresTenantSelection = false;

    private List<TenantChoice> tenants;

    public record TenantChoice(UUID tenantId, String companyName) {
    }
}