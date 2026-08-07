package com.nightgals.billing;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether one {@link PaymentProvider} bean is switched on.
 *
 * <p>Kept separate from the annotation so the parsing of the property - which is
 * also what {@link PaymentProviders} needs in order to name a default - has one
 * home rather than two that can disagree.
 */
public class PaymentProviderCondition implements Condition {

    static final String LIST_PROPERTY = "nightgals.monetization.providers";

    /** The pre-multi-provider property. One value, still honoured. */
    static final String LEGACY_PROPERTY = "nightgals.monetization.provider";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnPaymentProvider.class.getName());
        if (attributes == null) {
            return false;
        }
        String required = String.valueOf(attributes.get("value")).toUpperCase(Locale.ROOT);
        boolean matchIfMissing = Boolean.TRUE.equals(attributes.get("matchIfMissing"));

        Set<String> enabled = enabledProviders(
                context.getEnvironment().getProperty(LIST_PROPERTY),
                context.getEnvironment().getProperty(LEGACY_PROPERTY));

        return enabled.isEmpty() ? matchIfMissing : enabled.contains(required);
    }

    /**
     * The configured provider names, upper-cased.
     *
     * <p>{@code providers} wins when set; otherwise the single {@code provider}
     * stands in for a one-element list. Empty means nothing was configured, which
     * the caller reads as "use the default", not as "take no payments".
     */
    static Set<String> enabledProviders(String list, String legacy) {
        String raw = list == null || list.isBlank() ? legacy : list;
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
