package com.admtechhub.maestrohr.subscription;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresFeature} on Spring beans. Resolves the tenant from
 * {@link com.admtechhub.maestrohr.auth.TenantContext} (never from method arguments) via
 * {@link FeatureFlagService}, and throws {@link FeatureNotAvailableException} (→ HTTP 402)
 * before the method body runs when the feature is not available.
 *
 * <p>A method-level {@code @RequiresFeature} takes precedence over a type-level one.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class FeatureCheckAspect {

    private final FeatureFlagService featureFlagService;

    @Before("@annotation(com.admtechhub.maestrohr.subscription.RequiresFeature) "
            + "|| @within(com.admtechhub.maestrohr.subscription.RequiresFeature)")
    public void enforceFeature(JoinPoint joinPoint) {
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

        featureFlagService.requireFeature(required.value());
    }
}
