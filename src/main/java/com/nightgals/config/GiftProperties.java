package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What can be sent to a creator while she is live.
 *
 * <p>Configuration rather than a table, for the same reason creator packages
 * are: the catalogue is a handful of fixed items that change when the business
 * changes, not data anybody edits at runtime. A gift whose price moved keeps
 * every {@code Gift} row already sent at the price it was sent for, because the
 * amount is copied onto the row rather than looked up through the code.
 */
@ConfigurationProperties(prefix = "nightgals.gifts")
public record GiftProperties(

        /** False hides the catalogue and refuses every send. */
        boolean enabled,

        List<Item> catalogue) {

    /**
     * One sendable item.
     *
     * @param code    stable identifier, uppercase. What the client sends.
     * @param label   what the sender reads on the button.
     * @param icon    an emoji, so a client can render the catalogue with no
     *                assets of its own and no round trip for images.
     * @param priceMinor what it costs the sender, in the platform's currency.
     */
    public record Item(String code, String label, String icon, long priceMinor) {
    }

    public List<Item> items() {
        return catalogue == null ? List.of() : catalogue;
    }

    /** Case-insensitive, because a client sending "rose" means ROSE. */
    public Optional<Item> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        return items().stream()
                .filter(item -> item.code().toUpperCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
