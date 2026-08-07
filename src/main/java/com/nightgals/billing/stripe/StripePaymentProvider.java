package com.nightgals.billing.stripe;

import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.PaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.common.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Card payments, through Stripe's hosted Checkout.
 *
 * <p>Enabled by listing {@code stripe} in {@code nightgals.monetization.providers},
 * and chosen per checkout by a client sending {@code method: STRIPE} - or
 * {@code CARD}, which is what the payer thinks they are choosing and does not
 * bind the client to who processes it.
 *
 * <p>Nothing settles here. Creating a session only opens a page; whether anybody
 * completes it is a later, separate event that arrives as a signed webhook or is
 * discovered by {@link StripeReconciler}. So the purchase stays PENDING and the
 * client polls it - the same contract Mobile Money has, with a browser in place
 * of a handset prompt.
 *
 * <p>Hosted rather than card fields of our own, deliberately: card details never
 * touch this server, which keeps the platform out of PCI scope entirely. The
 * cost is a redirect out of the app and back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPaymentProvider("stripe")
public class StripePaymentProvider implements PaymentProvider {

    private final StripeGateway gateway;

    @Override
    public String name() {
        return "STRIPE";
    }

    /**
     * What the payer is actually choosing. Keeping {@code CARD} pointing here
     * means the processor could be replaced without a client release.
     */
    @Override
    public Set<String> aliases() {
        return Set.of("CARD");
    }

    @Override
    public String label() {
        return "Card";
    }

    @Override
    public String description() {
        return "Visa, Mastercard and others, on a secure Stripe page.";
    }

    /** The publishable key. Public by design - see {@link PaymentProvider#clientKey()}. */
    @Override
    public String clientKey() {
        return gateway.publishableKey();
    }

    @Override
    public PaymentInstruction startPayment(Purchase purchase) {
        Session session;
        try {
            session = gateway.createSession(purchase, describe(purchase),
                    purchase.getUser().getEmail());
        } catch (StripeException e) {
            // Worth 503 rather than 500: nothing about the request was wrong, and
            // a client that retries in a moment may well succeed.
            log.error("Stripe session for purchase {} rejected: {}", purchase.getId(), e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_unavailable",
                    "Card payments are not responding. Try again in a moment.");
        }

        if (session.getUrl() == null || session.getUrl().isBlank()) {
            // Stripe returns no url for sessions it does not expect a browser to
            // open. Nothing downstream can do anything useful with that here.
            log.error("Stripe session {} for purchase {} came back with no url",
                    session.getId(), purchase.getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stripe_unavailable",
                    "Card payments are not responding. Try again in a moment.");
        }

        log.info("Stripe checkout {} opened for purchase {}", session.getId(), purchase.getId());

        return new PaymentInstruction(
                session.getId(),
                PaymentInstruction.Action.REDIRECT,
                session.getUrl(),
                null);
    }

    /** Never true. The payer has to complete a page, in their own time. */
    @Override
    public boolean settlesImmediately() {
        return false;
    }

    /**
     * What the payer sees on the Stripe page and on their statement line.
     *
     * <p>Worth the detail: "Nightgals" alone on a card statement is what people
     * charge back as unrecognised, and a creator's own caption is what they will
     * recognise.
     */
    private static String describe(Purchase purchase) {
        return switch (purchase.getType()) {
            case MEDIA_UNLOCK -> {
                var media = purchase.getMedia();
                String caption = media.getCaption();
                yield caption != null && !caption.isBlank()
                        ? caption
                        : (media.getType() == com.nightgals.media.MediaType.VIDEO
                                ? "A video by " + media.getUser().getUsername()
                                : "A photo by " + media.getUser().getUsername());
            }
            case LIVE_ACCESS -> "Live: " + purchase.getLiveSession().getTitle();
            case CALL_BOOKING -> purchase.getCall().getDurationMinutes()
                                 + "-minute call with " + purchase.getCall().getCreator().getUsername();
            case CREATOR_PACKAGE -> purchase.getPackageCode() == null
                    ? "Nightgals creator package"
                    : "Nightgals " + purchase.getPackageCode().name().replace('_', ' ').toLowerCase()
                      + " package";
            case LIVE_EXTENSION -> purchase.getExtensionMinutes() + " extra live minutes";
            case PROFILE_UNLOCK, SUBSCRIPTION -> "Nightgals access";
        };
    }
}
