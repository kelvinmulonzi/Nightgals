package com.nightgals.billing;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a {@link PaymentProvider} only when the deployment has switched it on.
 *
 * <p>Replaces the {@code @ConditionalOnProperty(havingValue = "...")} these beans
 * used to carry. That annotation compares one property against one value, which
 * made the providers mutually exclusive - fine while the platform took Mobile
 * Money <i>or</i> cards, wrong now that a buyer picks between them at checkout.
 *
 * <p>Reads {@code nightgals.monetization.providers}, a comma-separated list, and
 * falls back to the older single-valued {@code nightgals.monetization.provider}
 * when that list is absent. The fallback is what keeps existing deployments and
 * the test suite - which sets {@code provider=manual} - working untouched.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(PaymentProviderCondition.class)
public @interface ConditionalOnPaymentProvider {

    /** The provider's {@link PaymentProvider#name()}, case-insensitive. */
    String value();

    /**
     * Whether to register when nothing is configured at all.
     *
     * <p>True for exactly one provider - the auto-settling one - so that an
     * application with no monetisation configuration still starts with something
     * able to answer a checkout.
     */
    boolean matchIfMissing() default false;
}
