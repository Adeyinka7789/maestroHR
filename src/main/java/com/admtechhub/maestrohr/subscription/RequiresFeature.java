package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a method (or every method of a type) behind a subscription feature.
 *
 * <p>{@link FeatureCheckAspect} intercepts annotated beans, resolves the current tenant
 * from {@link com.admtechhub.maestrohr.auth.TenantContext}, and throws
 * {@link FeatureNotAvailableException} (→ HTTP 402) when the tenant's plan does not include
 * the feature. A method-level annotation overrides a type-level one.
 *
 * <p>The feature-to-plan matrix lives in
 * {@link com.admtechhub.maestrohr.tenant.SubscriptionPlan}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFeature {
    SubscriptionFeature value();
}
