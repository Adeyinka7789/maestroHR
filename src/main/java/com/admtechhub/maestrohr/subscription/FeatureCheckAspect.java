package com.admtechhub.maestrohr.subscription;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresFeature} on Spring beans. Resolves the tenant from
 * {@link com.admtechhub.maestrohr.auth.TenantContext} (never from method arguments) via
 * {@link FeatureAccessService}, and throws a {@link FeatureAccessException} — 404 when the
 * platform flag is off, 402 when the plan lacks the feature — before the method body runs.
 *
 * <p>A method-level {@code @RequiresFeature} takes precedence over a type-level one.
 *
 * <p>Platform owners ({@code ROLE_SUPER_ADMIN}) are exempt: subscription feature gates are a
 * per-tenant billing concern, and the platform owner operates across tenants, so the check is
 * skipped entirely for them. In non-request contexts (async/jobs) no authentication is bound,
 * so the gate is enforced as normal.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class FeatureCheckAspect {

    private final FeatureAccessService featureAccessService;

    @Before("@annotation(com.admtechhub.maestrohr.subscription.RequiresFeature) "
            + "|| @within(com.admtechhub.maestrohr.subscription.RequiresFeature)")
    public void enforceFeature(JoinPoint joinPoint) {
        // Platform owners are never blocked by a tenant's subscription feature gates.
        if (isSuperAdmin()) {
            return;
        }

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();

        // Method-level annotation wins; fall back to the declaring type's annotation.
        RequiresFeature required = AnnotatedElementUtils.findMergedAnnotation(method, RequiresFeature.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    joinPoint.getTarget().getClass(), RequiresFeature.class);
        }
        if (required == null) {
            return;
        }

        featureAccessService.require(required.value());
    }

    /** True when the authenticated principal holds {@code ROLE_SUPER_ADMIN} (the platform owner). */
    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
