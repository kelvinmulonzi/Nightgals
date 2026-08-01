package com.nightgals.billing;

import com.nightgals.common.ApiException;
import com.nightgals.common.Money;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.live.LiveSession;
import com.nightgals.media.MediaAsset;
import org.springframework.stereotype.Service;

/**
 * What one item costs.
 *
 * <p>The creator sets it, per video and per broadcast. This resolves the price
 * actually charged - hers if she named one, the platform default if she did not -
 * and keeps her inside the bounds when she does.
 *
 * <p>Separate from {@link BillingService} because the answer is needed in places
 * that are not buying anything: a gallery listing shows a price on every locked
 * tile, and a profile shows one on every scheduled stream.
 */
@Service
public class ItemPricingService {

    private final MonetizationProperties properties;

    public ItemPricingService(MonetizationProperties properties) {
        this.properties = properties;
    }

    public String currency() {
        return properties.currency();
    }

    /** What a viewer pays for this item. Never null - an unpriced item still sells. */
    public long priceOf(MediaAsset asset) {
        return asset.getUnlockPriceMinor() != null
                ? asset.getUnlockPriceMinor()
                : properties.itemPricing().defaultPriceMinor();
    }

    public long priceOf(LiveSession session) {
        return session.getAccessPriceMinor() != null
                ? session.getAccessPriceMinor()
                : properties.itemPricing().defaultPriceMinor();
    }

    /** True when the creator named this price herself. */
    public boolean isCustom(MediaAsset asset) {
        return asset.getUnlockPriceMinor() != null;
    }

    public boolean isCustom(LiveSession session) {
        return session.getAccessPriceMinor() != null;
    }

    /** The same number a client would render, so nothing has to divide by 100. */
    public String display(long priceMinor) {
        return Money.plain(priceMinor, properties.currency());
    }

    /**
     * Keeps a creator's price inside the platform's bounds.
     *
     * <p>The floor protects the commission from being priced into irrelevance;
     * the ceiling catches the extra zero somebody typed by accident, which is
     * otherwise only discovered when nothing sells for a month.
     *
     * @param priceMinor the requested price, or null to clear it back to the default
     */
    public Long validate(Long priceMinor) {
        if (priceMinor == null) {
            return null;
        }
        MonetizationProperties.ItemPricing rules = properties.itemPricing();
        if (priceMinor < rules.floor()) {
            throw ApiException.badRequest("price_too_low",
                    "The lowest you can charge is " + Money.withCurrency(rules.floor(), currency()) + ".");
        }
        if (priceMinor > rules.ceiling()) {
            throw ApiException.badRequest("price_too_high",
                    "The most you can charge is " + Money.withCurrency(rules.ceiling(), currency()) + ".");
        }
        return priceMinor;
    }

    /** How long a bought item stays open. Null means it never expires. */
    public java.time.Duration accessDuration() {
        return properties.itemPricing().accessDuration();
    }
}
