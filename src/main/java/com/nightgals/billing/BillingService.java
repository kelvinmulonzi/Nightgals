package com.nightgals.billing;

import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.EntitlementResponse;
import com.nightgals.billing.dto.PurchaseResponse;
import com.nightgals.calls.VideoCall;
import com.nightgals.common.ApiException;
import com.nightgals.common.Money;
import com.nightgals.common.PageResponse;
import com.nightgals.config.CreatorPackageProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.earnings.EarningsService;
import com.nightgals.live.LiveSession;
import com.nightgals.live.LiveSessionRepository;
import com.nightgals.mail.EmailService;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaStatus;
import com.nightgals.referral.CreditService;
import com.nightgals.referral.ReferralService;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Creating purchases and turning settled ones into access.
 *
 * <p>Four things can be bought, and they divide cleanly in two:
 *
 * <ul>
 *   <li><b>Viewers pay creators</b> - one video, one broadcast, or one private
 *       call, each at the price its creator set.
 *   <li><b>Creators pay the platform</b> - a weekly package.
 * </ul>
 *
 * <p>{@link #settle} is the seam a real payment provider plugs into: a webhook,
 * an STK-push callback or an admin confirmation all end up there, and only it
 * grants anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final PurchaseRepository purchaseRepository;
    private final MediaUnlockRepository mediaUnlockRepository;
    private final LiveAccessRepository liveAccessRepository;
    private final MediaRepository mediaRepository;
    private final UserRepository userRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final EntitlementService entitlementService;
    private final ItemPricingService pricing;
    private final CreatorPackageService creatorPackageService;
    private final CreditService creditService;
    private final ReferralService referralService;
    private final PaymentProvider paymentProvider;
    private final EarningsService earningsService;
    private final EmailService emailService;
    private final MonetizationProperties properties;

    // ---------------------------------------------------------------- buying

    /** A viewer buying one photo or video, paying by a means that needs no details. */
    public CheckoutResponse unlockMedia(User buyer, UUID mediaId) {
        return unlockMedia(buyer, mediaId, null);
    }

    /** A viewer buying one photo or video. */
    @Transactional
    public CheckoutResponse unlockMedia(User buyer, UUID mediaId, String payerMsisdn) {
        requireMonetisationOn();

        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));

        if (asset.getStatus() != MediaStatus.APPROVED) {
            throw ApiException.notFound("Media");
        }
        if (asset.getUser().getId().equals(buyer.getId())) {
            throw ApiException.badRequest("self_purchase", "This is already yours");
        }
        if (asset.isFree()) {
            throw ApiException.badRequest("already_free", "This one is free to watch");
        }
        if (entitlementService.canView(buyer, asset)) {
            throw ApiException.conflict("already_unlocked", "You already have this");
        }

        // Reuse a payment the buyer already started rather than opening a second.
        // The price is fixed when the purchase is created: a creator changing hers
        // mid-checkout must not change what somebody is being charged.
        Purchase purchase = purchaseRepository.findPendingForMedia(buyer.getId(), mediaId)
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.MEDIA_UNLOCK)
                        .media(asset)
                        .amountMinor(pricing.priceOf(asset))
                        .currency(properties.currency())
                        .status(PurchaseStatus.PENDING)
                        .provider(paymentProvider.name())
                        .build()));

        return checkout(buyer, purchase, payerMsisdn);
    }

    /** A viewer buying entry to one broadcast, paying by a means that needs no details. */
    public CheckoutResponse buyLiveAccess(User buyer, UUID sessionId) {
        return buyLiveAccess(buyer, sessionId, null);
    }

    /** A viewer buying entry to one broadcast. */
    @Transactional
    public CheckoutResponse buyLiveAccess(User buyer, UUID sessionId, String payerMsisdn) {
        requireMonetisationOn();

        LiveSession session = liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Live session"));

        if (session.getHost().getId().equals(buyer.getId())) {
            throw ApiException.badRequest("self_purchase", "This is your own broadcast");
        }
        if (session.isFree()) {
            throw ApiException.badRequest("already_free", "This broadcast is open to everyone");
        }
        if (entitlementService.canJoin(buyer, session)) {
            throw ApiException.conflict("already_unlocked", "You already have access to this");
        }

        Purchase purchase = purchaseRepository.findPendingForLive(buyer.getId(), sessionId)
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.LIVE_ACCESS)
                        .liveSession(session)
                        .amountMinor(pricing.priceOf(session))
                        .currency(properties.currency())
                        .status(PurchaseStatus.PENDING)
                        .provider(paymentProvider.name())
                        .build()));

        return checkout(buyer, purchase, payerMsisdn);
    }

    /**
     * Paying for a call that has already been booked.
     *
     * <p>Created by {@code CallService}, which owns the slot and the price; this
     * only takes the money for it.
     */
    public CheckoutResponse payForCall(User buyer, VideoCall call) {
        return payForCall(buyer, call, null);
    }

    @Transactional
    public CheckoutResponse payForCall(User buyer, VideoCall call, String payerMsisdn) {
        requireMonetisationOn();

        Purchase purchase = purchaseRepository.findPendingForCall(buyer.getId(), call.getId())
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.CALL_BOOKING)
                        .call(call)
                        .amountMinor(call.getPriceMinor())
                        .currency(call.getCurrency())
                        .status(PurchaseStatus.PENDING)
                        .provider(paymentProvider.name())
                        .build()));

        return checkout(buyer, purchase, payerMsisdn);
    }

    /**
     * A creator buying the right to publish.
     *
     * <p>Not gated on identity verification: letting somebody pay while their
     * documents are in review means the package is live the moment they are
     * approved, rather than adding a second wait to the end of the first.
     */
    public CheckoutResponse buyCreatorPackage(User creator, String packageCode) {
        return buyCreatorPackage(creator, packageCode, null);
    }

    @Transactional
    public CheckoutResponse buyCreatorPackage(User creator, String packageCode, String payerMsisdn) {
        CreatorPackageCode code = creatorPackageService.parseCode(packageCode);
        if (!creatorPackageService.packagesRequired()) {
            throw ApiException.conflict("packages_disabled",
                    "Posting is currently free - there is nothing to buy");
        }
        CreatorPackageProperties.Package config = creatorPackageService.configFor(code);

        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(creator)
                .type(PurchaseType.CREATOR_PACKAGE)
                .packageCode(code)
                .amountMinor(config.priceMinor())
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(paymentProvider.name())
                .build());

        return checkout(creator, purchase, payerMsisdn);
    }

    /**
     * Digits only, so `+237 689 686 224` and `237689686224` are one number.
     *
     * <p>Deliberately not validating the country or length here - the shape is
     * checked at the edge, and a payment provider rejecting a number it does not
     * recognise is a better error than this guessing which numbers exist.
     */
    private static String normaliseMsisdn(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }

    // ---------------------------------------------------------------- checkout

    /**
     * Takes whatever credit the buyer has, then asks the provider for the rest.
     *
     * <p>Credit is applied before the provider is involved, so a purchase covered
     * entirely by referral credit never reaches a payment at all - it simply
     * settles. That is the whole point of credit being spendable "toward
     * subscriptions or unlocking premium features".
     */
    private CheckoutResponse checkout(User buyer, Purchase purchase, String payerMsisdn) {
        // Set on every attempt, not only the first. A purchase left PENDING is
        // reused when the buyer tries again, and the second attempt may well be
        // from a different handset - the number recorded should be the one being
        // charged now, not the one that already failed.
        String normalised = normaliseMsisdn(payerMsisdn);
        if (normalised != null) {
            purchase.setPayerMsisdn(normalised);
        }

        CreditService.Applied credit = creditService.applyTo(buyer, purchase);
        purchase.setCreditAppliedMinor(credit.creditUsedMinor());

        if (credit.coversEverything()) {
            grant(purchase, "CREDIT-" + purchase.getId());
            return new CheckoutResponse(
                    PurchaseResponse.of(purchase),
                    PaymentProvider.PaymentInstruction.Action.NONE,
                    null,
                    null);
        }

        var instruction = paymentProvider.startPayment(purchase);
        if (instruction.reference() != null) {
            purchase.setProviderReference(instruction.reference());
        }

        // A provider that settles on the spot has nothing to wait for, so access
        // is granted before the response is written rather than leaving the client
        // polling a purchase that is never going to change.
        if (paymentProvider.settlesImmediately()) {
            grant(purchase, instruction.reference());
        }

        return new CheckoutResponse(
                PurchaseResponse.of(purchase),
                instruction.action(),
                instruction.redirectUrl(),
                instruction.instructions());
    }

    // ---------------------------------------------------------------- settlement

    /**
     * Marks a purchase paid and grants what it bought.
     *
     * <p>Idempotent: settling an already-COMPLETED purchase changes nothing, so a
     * webhook that fires twice cannot grant two packages.
     */
    @Transactional
    public PurchaseResponse settle(UUID purchaseId, String providerReference) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> ApiException.notFound("Purchase"));

        if (purchase.getStatus() == PurchaseStatus.COMPLETED) {
            return PurchaseResponse.of(purchase);
        }
        if (purchase.getStatus() != PurchaseStatus.PENDING) {
            throw ApiException.conflict("not_pending",
                    "Purchase is " + purchase.getStatus() + " and cannot be settled");
        }

        grant(purchase, providerReference);
        return PurchaseResponse.of(purchase);
    }

    /**
     * Everything that happens when a payment is confirmed.
     *
     * <p>Shared by every route to COMPLETED - an out-of-band settlement, a
     * provider that settles immediately, and a purchase paid entirely in credit -
     * so none of them can drift from the others on what access is granted or who
     * gets paid.
     */
    private void grant(Purchase purchase, String providerReference) {
        purchase.setStatus(PurchaseStatus.COMPLETED);
        purchase.setCompletedAt(Instant.now());
        if (providerReference != null && !providerReference.isBlank()) {
            purchase.setProviderReference(providerReference);
        }

        switch (purchase.getType()) {
            case MEDIA_UNLOCK -> {
                grantMediaUnlock(purchase);
                earningsService.recordItemEarning(purchase, purchase.getMedia().getUser());
                notifyItemSold(purchase, purchase.getMedia().getUser(),
                        describe(purchase.getMedia()));
            }
            case LIVE_ACCESS -> {
                grantLiveAccess(purchase);
                earningsService.recordItemEarning(purchase, purchase.getLiveSession().getHost());
                notifyItemSold(purchase, purchase.getLiveSession().getHost(),
                        purchase.getLiveSession().getTitle());
            }
            case CALL_BOOKING -> {
                // Confirmed here rather than in CallService, which would be a
                // dependency cycle: CallService already calls into billing to take
                // the money. A booking nobody paid for holds its slot and hands out
                // no room, so this is the moment it becomes real.
                if (purchase.getCall().getStatus() == com.nightgals.calls.CallStatus.PENDING_PAYMENT) {
                    purchase.getCall().setStatus(com.nightgals.calls.CallStatus.CONFIRMED);
                }
                earningsService.recordItemEarning(purchase, purchase.getCall().getCreator());
                notifyItemSold(purchase, purchase.getCall().getCreator(),
                        purchase.getCall().getDurationMinutes() + "-minute call");
            }
            // Money towards the platform, not towards a creator, so deliberately
            // no earnings entry - and the only type that pays a referral bonus.
            case CREATOR_PACKAGE -> {
                grantCreatorPackage(purchase);
                referralService.onPurchaseSettled(purchase);
            }
            case PROFILE_UNLOCK, SUBSCRIPTION -> log.warn(
                    "Purchase {} is a retired type ({}); nothing granted",
                    purchase.getId(), purchase.getType());
        }

        log.info("Purchase {} settled ({})", purchase.getId(), purchase.getType());
    }

    @Transactional
    public PurchaseResponse fail(UUID purchaseId, String reason) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> ApiException.notFound("Purchase"));
        if (purchase.getStatus() != PurchaseStatus.PENDING) {
            throw ApiException.conflict("not_pending", "Purchase is " + purchase.getStatus());
        }
        purchase.setStatus(PurchaseStatus.FAILED);
        purchase.setFailureReason(reason);

        // Credit taken for something that did not happen goes back.
        if (purchase.getCreditAppliedMinor() > 0) {
            creditService.refund(purchase.getUser(), purchase.getCreditAppliedMinor(), purchase,
                    "Refund for failed purchase " + purchase.getId());
            purchase.setCreditAppliedMinor(0);
        }
        return PurchaseResponse.of(purchase);
    }

    // ---------------------------------------------------------------- granting

    private void grantMediaUnlock(Purchase purchase) {
        if (mediaUnlockRepository.existsByViewerIdAndMediaId(
                purchase.getUser().getId(), purchase.getMedia().getId())) {
            return;
        }
        mediaUnlockRepository.save(MediaUnlock.builder()
                .viewer(purchase.getUser())
                .media(purchase.getMedia())
                .source(UnlockSource.PURCHASE)
                .purchase(purchase)
                .build());
    }

    private void grantLiveAccess(Purchase purchase) {
        if (liveAccessRepository.existsByViewerIdAndSessionId(
                purchase.getUser().getId(), purchase.getLiveSession().getId())) {
            return;
        }
        liveAccessRepository.save(LiveAccess.builder()
                .viewer(purchase.getUser())
                .session(purchase.getLiveSession())
                .source(UnlockSource.PURCHASE)
                .purchase(purchase)
                .build());
    }

    private void grantCreatorPackage(Purchase purchase) {
        var granted = creatorPackageService.grant(
                purchase.getUser(), purchase.getPackageCode(), purchase);
        CreatorPackageProperties.Package config =
                creatorPackageService.configFor(purchase.getPackageCode());

        emailService.sendPackageReceipt(
                purchase.getUser().getEmail(),
                purchase.getUser().getUsername(),
                config.label(),
                Money.withCurrency(purchase.getAmountMinor(), purchase.getCurrency()),
                granted.getExpiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                config.maxPhotos(),
                config.maxPremiumVideos());
    }

    /** Staff comp: access to one item without payment. */
    @Transactional
    public void grantMedia(UUID viewerId, UUID mediaId) {
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> ApiException.notFound("Viewer"));
        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));

        if (mediaUnlockRepository.existsByViewerIdAndMediaId(viewerId, mediaId)) {
            return;
        }
        mediaUnlockRepository.save(MediaUnlock.builder()
                .viewer(viewer)
                .media(asset)
                .source(UnlockSource.GRANT)
                .build());
        log.info("Granted media {} to {}", mediaId, viewerId);
    }

    // ---------------------------------------------------------------- notices

    /**
     * Tells both sides an item sold.
     *
     * <p>Best-effort by design - {@link EmailService} sends these asynchronously
     * and swallows failures, because a receipt that does not arrive must never
     * roll back a payment that did.
     */
    private void notifyItemSold(Purchase purchase, User creator, String what) {
        String amount = Money.withCurrency(purchase.getAmountMinor(), purchase.getCurrency());
        emailService.sendItemReceipt(
                purchase.getUser().getEmail(), creator.getUsername(), what, amount);
        emailService.sendItemSoldNotice(
                creator.getEmail(), creator.getUsername(), what, amount);
    }

    private String describe(MediaAsset asset) {
        if (asset.getCaption() != null && !asset.getCaption().isBlank()) {
            return asset.getCaption();
        }
        return asset.getType() == com.nightgals.media.MediaType.VIDEO ? "a video" : "a photo";
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public EntitlementResponse entitlements(User viewer) {
        long credit = creditService.balanceOf(viewer.getId());
        String provider = paymentProvider.name();
        return new EntitlementResponse(
                viewer.isOnTrial(),
                viewer.getTrialEndsAt(),
                mediaUnlockRepository.countByViewerId(viewer.getId()),
                credit,
                Money.plain(credit, properties.currency()),
                properties.currency(),
                provider,
                // Asked from the provider's own name rather than from configuration,
                // so swapping the bean cannot leave the client asking for a number
                // nobody will charge - or worse, not asking for one that is required.
                "MOMO".equals(provider));
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseResponse> history(UUID userId, Pageable pageable) {
        return PageResponse.from(
                purchaseRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable),
                PurchaseResponse::of);
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseResponse> pending(Pageable pageable) {
        return PageResponse.from(
                purchaseRepository.findByStatus(PurchaseStatus.PENDING, pageable),
                PurchaseResponse::of);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return purchaseRepository.countByStatus(PurchaseStatus.PENDING);
    }

    private void requireMonetisationOn() {
        if (!properties.enabled()) {
            throw ApiException.conflict("monetisation_disabled",
                    "Paid access is switched off; all content is currently free");
        }
    }
}
