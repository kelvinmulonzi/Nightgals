package com.nightgals.live;

import com.nightgals.common.ApiException;
import com.nightgals.common.Money;
import com.nightgals.config.GiftProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.earnings.EarningsService;
import com.nightgals.common.PageResponse;
import com.nightgals.live.dto.GiftFeedResponse;
import com.nightgals.live.dto.GiftHistoryResponse;
import com.nightgals.live.dto.GiftTotalsResponse;
import com.nightgals.live.dto.GiftOptionResponse;
import com.nightgals.live.dto.GiftResponse;
import com.nightgals.referral.CreditService;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Sending money to a creator while she is broadcasting.
 *
 * <p>Deliberately not a payment. The money was taken when the sender topped up
 * their balance; this only moves it, which is what makes a gift instant enough
 * to be worth sending at all. A card redirect per gift would take the sender out
 * of the broadcast and back for every one.
 *
 * <p>It is also what keeps the platform on the right side of the line: nobody is
 * transmitting money between two people. The platform sold the balance, and owes
 * the creator a share of what was spent - the same marketplace shape as every
 * other sale here, with the same commission and the same hold before payout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftService {

    private final GiftRepository giftRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final CreditService creditService;
    private final EarningsService earningsService;
    private final GiftProperties gifts;
    private final MonetizationProperties monetization;

    /** What can be sent, in ascending price so a picker reads naturally. */
    public List<GiftOptionResponse> catalogue() {
        requireGiftsOn();
        return gifts.items().stream()
                .sorted(Comparator.comparingLong(GiftProperties.Item::priceMinor))
                .map(item -> GiftOptionResponse.of(item, monetization.currency()))
                .toList();
    }

    /**
     * Sends one gift.
     *
     * <p>One transaction covering the debit, the receipt and the creator's
     * earning: a balance taken without the creator being paid, or a creator paid
     * from a balance that was never debited, are both worse than the send simply
     * failing.
     */
    @Transactional
    public GiftResponse send(User sender, UUID sessionId, String giftCode, String message) {
        requireGiftsOn();

        GiftProperties.Item item = gifts.find(giftCode)
                .orElseThrow(() -> ApiException.badRequest("unknown_gift",
                        "No such gift: " + giftCode));

        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Live session"));

        // Ended broadcasts refuse gifts. Not pedantry: the sender is paying for a
        // moment on screen in front of an audience, and neither exists any more.
        if (!session.isLive()) {
            throw ApiException.conflict("not_live",
                    "This broadcast is not live, so gifts cannot be sent to it");
        }

        User creator = session.getHost();
        if (creator.getId().equals(sender.getId())) {
            // Otherwise a creator could recycle her own balance into earnings and
            // withdraw it, turning a payout into a way to cash out a card.
            throw ApiException.badRequest("self_gift", "You cannot send a gift to yourself");
        }

        // Throws 409 insufficient_credit, which is the client's cue to open the
        // top-up screen. All-or-nothing on purpose: half a gift is not a thing.
        creditService.spend(sender, item.priceMinor(),
                "Gift " + item.code() + " during broadcast " + session.getId());

        Gift gift = giftRepository.save(Gift.builder()
                .sender(sender)
                .creator(creator)
                .liveSession(session)
                .giftCode(item.code())
                .giftLabel(item.label())
                .giftIcon(item.icon())
                .amountMinor(item.priceMinor())
                .currency(monetization.currency())
                .message(trimmed(message))
                .build());

        earningsService.recordGiftEarning(creator, item.priceMinor(), monetization.currency());

        log.info("{} sent {} to {} during broadcast {}",
                sender.getId(),
                Money.withCurrency(item.priceMinor(), monetization.currency()),
                creator.getId(), session.getId());

        return GiftResponse.of(gift);
    }

    /* ── Beyond the broadcast ──────────────────────────────────── */

    /**
     * What this account has gifted and been gifted, all time.
     *
     * <p>Both figures matter and they are not the same kind of number. What was
     * sent is money that has left: it is spent, final, and the sender is owed
     * nothing back. What was received is <b>gross</b> - the platform's cut comes
     * out before a creator sees it, and what she can actually withdraw lives on
     * the earnings ledger. Presenting the second as a balance would be a lie
     * about money, which is the worst kind to tell on this screen.
     */
    @Transactional(readOnly = true)
    public GiftTotalsResponse totalsFor(UUID userId) {
        long sent = giftRepository.totalSentBy(userId);
        long received = giftRepository.totalReceivedBy(userId);
        String currency = monetization.currency();
        return new GiftTotalsResponse(
                sent, Money.plain(sent, currency), giftRepository.countBySenderId(userId),
                received, Money.plain(received, currency), giftRepository.countByCreatorId(userId),
                currency);
    }

    /**
     * Every gift this account has sent, newest first.
     *
     * <p>The room's feed empties when the broadcast ends, so without this the
     * only record a sender has of where their money went is a line in the credit
     * ledger reading "Gift ROSE during broadcast &lt;uuid&gt;".
     */
    @Transactional(readOnly = true)
    public PageResponse<GiftHistoryResponse> sentBy(UUID userId, Pageable pageable) {
        return PageResponse.from(
                giftRepository.findBySenderIdOrderByCreatedAtDesc(userId, pageable),
                GiftHistoryResponse::of);
    }

    /** Every gift this creator has received, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<GiftHistoryResponse> receivedBy(UUID userId, Pageable pageable) {
        return PageResponse.from(
                giftRepository.findByCreatorIdOrderByCreatedAtDesc(userId, pageable),
                GiftHistoryResponse::of);
    }

    /**
     * What has been sent, for a client catching up.
     *
     * <p>{@code since} absent means "I have just joined": the most recent gifts
     * are returned so the screen is not blank, rather than the whole history of a
     * long broadcast.
     *
     * <p>The response carries the server's clock as {@code until}, which the
     * client sends back next time. Deriving it from the last gift instead would
     * stall a quiet broadcast at an old timestamp; deriving it from the client's
     * own clock would replay or skip gifts whenever the two disagree.
     */
    @Transactional(readOnly = true)
    public GiftFeedResponse feed(UUID sessionId, Instant since) {
        if (!liveSessionRepository.existsById(sessionId)) {
            throw ApiException.notFound("Live session");
        }
        Instant until = Instant.now();

        List<Gift> found;
        if (since == null) {
            found = new ArrayList<>(giftRepository.findTop50ByLiveSessionIdOrderByCreatedAtDesc(sessionId));
            // Fetched newest-first to get the last 50, delivered oldest-first
            // because that is the order they happened in.
            java.util.Collections.reverse(found);
        } else {
            found = giftRepository.findSince(sessionId, since);
        }

        return new GiftFeedResponse(
                found.stream().map(GiftResponse::of).toList(),
                until,
                giftRepository.totalForSession(sessionId),
                Money.plain(giftRepository.totalForSession(sessionId), monetization.currency()),
                monetization.currency());
    }

    private void requireGiftsOn() {
        if (!gifts.enabled() || gifts.items().isEmpty()) {
            throw ApiException.conflict("gifts_disabled", "Gifts are not available here");
        }
    }

    private static String trimmed(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String clean = message.trim();
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}
