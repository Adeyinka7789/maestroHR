package com.admtechhub.maestrohr.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expiry-ms}")
    private long expiryMs;

    @Value("${security.jwt.refresh-expiry-ms}")
    private long refreshExpiryMs;

    @Value("${security.jwt.impersonation-expiry-ms}")
    private long impersonationExpiryMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email, String tenantId, String role) {
        return Jwts.builder()
                .subject(email)
                .claims(Map.of(
                        "tenantId", tenantId,
                        "role", role
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(String email, String tenantId) {
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("tenantId", tenantId, "type", "refresh"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Short-lived token (20 min) that lets a super-admin act as a target tenant user (Feature 4).
     * The {@code tenantId}/{@code role} claims are the <em>target</em> user's, so the impersonator
     * operates fully within the target tenant; {@code impersonation=true} and {@code impersonatedBy}
     * carry the originating admin's email so every audit row written during the session is tagged.
     *
     * <p>No refresh token is ever issued for impersonation: the session simply expires after
     * {@code impersonationExpiryMs}. (There is no refresh endpoint to harden — login/register are
     * the only token-issuing paths — so "block refresh" reduces to "never mint one here".)
     */
    public String generateImpersonationToken(String targetEmail, String targetTenantId,
                                             String targetRole, String adminEmail) {
        return Jwts.builder()
                .subject(targetEmail)
                .claims(Map.of(
                        "tenantId", targetTenantId,
                        "role", targetRole,
                        "impersonation", true,
                        "impersonatedBy", adminEmail
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + impersonationExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean isImpersonationToken(String token) {
        return Boolean.TRUE.equals(
                extractClaim(token, claims -> claims.get("impersonation", Boolean.class)));
    }

    public String extractImpersonatedBy(String token) {
        return extractClaim(token, claims -> claims.get("impersonatedBy", String.class));
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractTenantId(String token) {
        return extractClaim(token, claims -> claims.get("tenantId", String.class));
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(getClaims(token));
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}