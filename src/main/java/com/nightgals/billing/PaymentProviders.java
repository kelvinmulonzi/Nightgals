package com.nightgals.billing;

import com.nightgals.common.ApiException;
import com.nightgals.config.MonetizationProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The payment methods this deployment offers, and which one a checkout meant.
 *
 * <p>{@link BillingService} used to inject a single {@link PaymentProvider},
 * which made the choice a deployment-wide setting. It is a per-purchase one: a
 * viewer in Douala pays by MTN Mobile Money, a viewer paying by card goes
 * through Stripe, and both are switched on at the same time. So the providers
 * are injected as a list and resolved by the {@code method} the client sent.
 *
 * <p>Resolution is by {@link PaymentProvider#name()} or any of its
 * {@link PaymentProvider#aliases()}, case-insensitively. An absent or blank
 * method means the configured default, which keeps every existing client - none
 * of which sends one - working unchanged.
 */
@Slf4j
@Component
public class PaymentProviders {

    /** Every enabled provider by name and by alias. Insertion-ordered. */
    private final Map<String, PaymentProvider> byCode = new LinkedHashMap<>();

    /** Enabled providers in configuration order, without the alias duplicates. */
    private final List<PaymentProvider> enabled;

    private final PaymentProvider fallback;

    public PaymentProviders(List<PaymentProvider> providers, MonetizationProperties properties) {
        if (providers.isEmpty()) {
            // Spring would fail on the empty list anyway; saying why is kinder
            // than a NoSuchBeanDefinitionException three frames down.
            throw new IllegalStateException(
                    "No PaymentProvider is enabled. Set nightgals.monetization.providers to at "
                    + "least one of auto, manual, momo, stripe.");
        }
        this.enabled = inConfiguredOrder(providers, properties);

        for (PaymentProvider provider : enabled) {
            register(provider.name(), provider);
            provider.aliases().forEach(alias -> register(alias, provider));
        }
        this.fallback = resolveDefault(enabled, properties);
    }

    /**
     * The providers sorted the way configuration lists them.
     *
     * <p>Spring hands them over in bean-definition order, which is really
     * classpath scanning order - stable enough, but not anything an operator
     * chose. Since this order is what the picker renders and what the default
     * falls back to, leaving it to the scanner would mean the checkout screen
     * silently reordering itself when a class is renamed.
     *
     * <p>Anything enabled but unlisted - the auto provider registering because
     * nothing was configured at all - keeps its position at the end.
     */
    private static List<PaymentProvider> inConfiguredOrder(List<PaymentProvider> providers,
                                                           MonetizationProperties properties) {
        var configured = PaymentProviderCondition.enabledProviders(
                properties.providers(), properties.provider());

        List<PaymentProvider> ordered = new java.util.ArrayList<>(providers.size());
        for (String code : configured) {
            providers.stream()
                    .filter(provider -> matches(provider, code))
                    .findFirst()
                    .filter(provider -> !ordered.contains(provider))
                    .ifPresent(ordered::add);
        }
        providers.stream().filter(provider -> !ordered.contains(provider)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static boolean matches(PaymentProvider provider, String code) {
        return provider.name().equalsIgnoreCase(code)
               || provider.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(code));
    }

    private void register(String code, PaymentProvider provider) {
        PaymentProvider clash = byCode.putIfAbsent(code.toUpperCase(Locale.ROOT), provider);
        if (clash != null && clash != provider) {
            throw new IllegalStateException(
                    "Two payment providers both answer to '" + code + "': "
                    + clash.getClass().getSimpleName() + " and " + provider.getClass().getSimpleName());
        }
    }

    /**
     * Which provider a checkout that named none should use.
     *
     * <p>{@code default-provider} when it is set and enabled, otherwise the first
     * one configured. Deliberately not "whichever bean Spring happened to order
     * first" - that would make the behaviour of an unqualified checkout depend on
     * classpath scanning order.
     */
    private static PaymentProvider resolveDefault(List<PaymentProvider> providers,
                                                  MonetizationProperties properties) {
        String preferred = properties.defaultProvider();
        if (preferred != null && !preferred.isBlank()) {
            String wanted = preferred.trim();
            for (PaymentProvider provider : providers) {
                if (matches(provider, wanted)) {
                    return provider;
                }
            }
            log.warn("nightgals.monetization.default-provider is '{}', which is not enabled. "
                     + "Falling back to {}.", preferred, providers.getFirst().name());
        }
        return providers.getFirst();
    }

    @PostConstruct
    void report() {
        log.info("Payment methods enabled: {} (default {})",
                enabled.stream().map(PaymentProvider::name).toList(), fallback.name());
    }

    /**
     * The provider a checkout asked for.
     *
     * @throws ApiException 400 when the method is not one this deployment offers -
     *                      a typo or a stale client build, either way something
     *                      the caller must fix rather than be silently charged
     *                      through some other method
     */
    public PaymentProvider resolve(String method) {
        if (method == null || method.isBlank()) {
            return fallback;
        }
        PaymentProvider provider = byCode.get(method.trim().toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw ApiException.badRequest("unknown_payment_method",
                    "No such payment method: " + method + ". Available: "
                    + enabled.stream().map(PaymentProvider::name).toList());
        }
        return provider;
    }

    /** What the payment picker lists, in configuration order. */
    public List<PaymentProvider> enabled() {
        return enabled;
    }

    public PaymentProvider defaultProvider() {
        return fallback;
    }
}
