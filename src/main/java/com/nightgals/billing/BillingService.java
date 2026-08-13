package com.nightgals.billing;

import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.CreditBalanceResponse;
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
import java.time.LocalDate;
import java.util.List;
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
    private final PaymentProviders paymentProviders;
    private final EarningsService earningsService;
    private final com.nightgals.live.LiveQuotaService liveQuotaService;
    private final EmailService emailService;
    private final MonetizationProperties properties;

    /**
     * Providers that can be asked directly what happened to a payment.
     *
     * <p>A list, and possibly empty: which ones exist depends on what is
     * configured, and a deployment taking money by hand has none. Injected as a
     * collection rather than a named bean so this class does not have to know
     * about any particular provider - and lazily, because a reconciler needs
     * this service to do the settling and would otherwise close the circle.
     */
    private final org.springframework.beans.factory.ObjectProvider<PurchaseReconciler> reconcilerBeans;

    private java.util.List<PurchaseReconciler> reconcilers() {
        return reconcilerBeans.stream().toList();
    }

    // ---------------------------------------------------------------- buying

    /** A viewer buying one photo or video, on the deployment's default method. */
    public CheckoutResponse unlockMedia(User buyer, UUID mediaId) {
        return unlockMedia(buyer, mediaId, PaymentChoice.none());
    }

    /** A viewer buying one photo or video. */
    @Transactional
    public CheckoutResponse unlockMedia(User buyer, UUID mediaId, PaymentChoice choice) {
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

        PaymentProvider provider = paymentProviders.resolve(choice.method());

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
                        .provider(provider.name())
                        .build()));

        return checkout(buyer, purchase, provider, choice);
    }

    /**
     * One purchase, brought up to date with the provider before it is answered.
     *
     * <p>This is what the confirmation screen polls, and the reconciliation is
     * the point of it. A card payment is already paid by the time the browser
     * comes back, but the platform does not know until a webhook arrives - and
     * on a deployment with no reachable webhook endpoint that is the scheduled
     * sweep, up to two minutes later. Two minutes of spinner after handing over
     * a card reads as a payment that failed, and the next thing that happens is
     * somebody pays twice.
     *
     * <p>Asking the provider directly turns that into one round trip. The
     * webhook and the sweep both still run; they are the backstop for a payer
     * who closed the tab, rather than the only way anything settles.
     */
    @Transactional
    public PurchaseResponse purchase(UUID userId, UUID purchaseId) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> ApiException.notFound("Purchase"));
        if (!purchase.getUser().getId().equals(userId)) {
            // Not found rather than forbidden: whether somebody else's purchase
            // id exists is not this caller's business.
            throw ApiException.notFound("Purchase");
        }

        if (purchase.getStatus() == PurchaseStatus.PENDING) {
            reconcilers().stream()
                    .filter(r -> r.handles(purchase))
                    .findFirst()
                    .ifPresent(r -> {
                        try {
                            r.reconcileNow(purchase);
                        } catch (RuntimeException e) {
                            // The provider is unreachable or slow. Answer with
                            // what is on record - the poll will come round again,
                            // and reporting a failure here would be inventing one.
                            log.warn("Could not reconcile purchase {} on demand: {}",
                                    purchaseId, e.toString());
                        }
                    });
        }
        return PurchaseResponse.of(purchase);
    }

    /**
     * Everything this creator has locked right now, and what the lot costs.
     *
     * <p>The same method the purchase itself uses, so the figure on the profile
     * is the figure charged - a quote computed one way and a price computed
     * another is how somebody ends up paying a number they never saw.
     *
     * <p>Scoped to what this buyer does not already own. Somebody who bought two
     * clips is quoted for the rest, and somebody returning after the creator
     * posted more is quoted for the new ones alone.
     */
    @Transactional(readOnly = true)
    public UnlockAllQuote quoteUnlockAll(User buyer, UUID creatorId) {
        List<MediaAsset> items = lockedItemsFor(buyer, creatorId);
        long total = items.stream().mapToLong(pricing::priceOf).sum();
        long photos = items.stream()
                .filter(m -> m.getType() == com.nightgals.media.MediaType.PHOTO)
                .count();
        return new UnlockAllQuote(
                items.size(),
                (int) photos,
                items.size() - (int) photos,
                total,
                pricing.display(total),
                properties.currency());
    }

    /**
     * Buys every locked item this creator has posted so far, in one payment.
     *
     * <p>Deliberately not a subscription and not a claim on the profile: the ids
     * are pinned to the purchase, so what she posts tomorrow is hers to sell
     * again. See {@link PurchaseType#PROFILE_UNLOCK}.
     */
    @Transactional
    public CheckoutResponse unlockAll(User buyer, UUID creatorId, PaymentChoice choice) {
        requireMonetisationOn();

        if (buyer.getId().equals(creatorId)) {
            throw ApiException.badRequest("self_purchase", "This is already yours");
        }
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> ApiException.notFound("Member"));

        List<MediaAsset> items = lockedItemsFor(buyer, creatorId);
        if (items.isEmpty()) {
            throw ApiException.conflict("nothing_to_unlock",
                    "You already have everything this creator has posted.");
        }

        PaymentProvider provider = paymentProviders.resolve(choice.method());
        long total = items.stream().mapToLong(pricing::priceOf).sum();

        // Not reusing a PENDING bundle the way a single item does. The contents
        // are a snapshot: one started before the creator posted three more clips
        // is for a different set of things, and charging yesterday's total for
        // today's gallery would be wrong in whichever direction it landed.
        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(buyer)
                .type(PurchaseType.PROFILE_UNLOCK)
                .targetUser(creator)
                .bundleMediaIds(items.stream().map(MediaAsset::getId)
                        .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new)))
                .amountMinor(total)
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(provider.name())
                .build());

        return checkout(buyer, purchase, provider, choice);
    }

    /** This creator's approved, paid items that the buyer cannot already see. */
    private List<MediaAsset> lockedItemsFor(User buyer, UUID creatorId) {
        List<MediaAsset> approved = mediaRepository
                .findByUserIdAndStatusOrderByPositionAscCreatedAtAsc(creatorId, MediaStatus.APPROVED)
                .stream()
                .filter(m -> !m.isFree())
                .toList();
        if (approved.isEmpty()) {
            return List.of();
        }
        // One query for the whole gallery, and the same entitlement check the
        // gallery itself uses - so the bundle covers exactly the tiles a viewer
        // sees blurred, no more and no less.
        var viewable = entitlementService.viewableAmong(buyer, approved);
        return approved.stream().filter(m -> !viewable.contains(m.getId())).toList();
    }

    /**
     * A viewer loading their balance, which is what gifts are sent from.
     *
     * <p>The amount is the buyer's, not a creator's price, so it is validated
     * here rather than looked up. Deliberately not reusing a PENDING top-up the
     * way the item purchases do: somebody who started a 5 000 top-up and then
     * decided on 20 000 means the second figure, and silently charging the first
     * would be wrong.
     */
    @Transactional
    public CheckoutResponse buyCredit(User buyer, long amountMinor, PaymentChoice choice) {
        requireMonetisationOn();

        MonetizationProperties.CreditTopUp limits = properties.creditTopUp();
        if (limits == null) {
            throw ApiException.conflict("topup_disabled", "Balance top-ups are not available");
        }
        if (amountMinor < limits.floor() || amountMinor > limits.ceiling()) {
            throw ApiException.badRequest("invalid_amount",
                    "Top up between " + Money.withCurrency(limits.floor(), properties.currency())
                    + " and " + Money.withCurrency(limits.ceiling(), properties.currency()));
        }

        PaymentProvider provider = paymentProviders.resolve(choice.method());

        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(buyer)
                .type(PurchaseType.CREDIT_TOPUP)
                .amountMinor(amountMinor)
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(provider.name())
                .build());

        return checkout(buyer, purchase, provider, choice);
    }

    /** What is on account, and the bounds a top-up has to fall inside. */
    @Transactional(readOnly = true)
    public CreditBalanceResponse creditBalance(User user) {
        return CreditBalanceResponse.of(
                creditService.balanceOf(user.getId()),
                properties.creditTopUp(),
                properties.currency());
    }

    /** A viewer buying entry to one broadcast, on the deployment's default method. */
    public CheckoutResponse buyLiveAccess(User buyer, UUID sessionId) {
        return buyLiveAccess(buyer, sessionId, PaymentChoice.none());
    }

    /** A viewer buying entry to one broadcast. */
    @Transactional
    public CheckoutResponse buyLiveAccess(User buyer, UUID sessionId, PaymentChoice choice) {
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

        PaymentProvider provider = paymentProviders.resolve(choice.method());

        Purchase purchase = purchaseRepository.findPendingForLive(buyer.getId(), sessionId)
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.LIVE_ACCESS)
                        .liveSession(session)
                        .amountMinor(pricing.priceOf(session))
                        .currency(properties.currency())
                        .status(PurchaseStatus.PENDING)
                        .provider(provider.name())
                        .build()));

        return checkout(buyer, purchase, provider, choice);
    }

    /**
     * Paying for a call that has already been booked.
     *
     * <p>Created by {@code CallService}, which owns the slot and the price; this
     * only takes the money for it.
     */
    public CheckoutResponse payForCall(User buyer, VideoCall call) {
        return payForCall(buyer, call, PaymentChoice.none());
    }

    @Transactional
    public CheckoutResponse payForCall(User buyer, VideoCall call, PaymentChoice choice) {
        requireMonetisationOn();

        PaymentProvider provider = paymentProviders.resolve(choice.method());

        Purchase purchase = purchaseRepository.findPendingForCall(buyer.getId(), call.getId())
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.CALL_BOOKING)
                        .call(call)
                        .amountMinor(call.getPriceMinor())
                        .currency(call.getCurrency())
                        .status(PurchaseStatus.PENDING)
                        .provider(provider.name())
                        .build()));

        return checkout(buyer, purchase, provider, choice);
    }

    /**
     * A creator buying the right to publish.
     *
     * <p>Not gated on identity verification: letting somebody pay while their
     * documents are in review means the package is live the moment they are
     * approved, rather than adding a second wait to the end of the first.
     */
    public CheckoutResponse buyCreatorPackage(User creator, String packageCode) {
        return buyCreatorPackage(creator, packageCode, PaymentChoice.none());
    }

    @Transactional
    public CheckoutResponse buyCreatorPackage(User creator, String packageCode, PaymentChoice choice) {
        CreatorPackageCode code = creatorPackageService.parseCode(packageCode);
        if (!creatorPackageService.packagesRequired()) {
            throw ApiException.conflict("packages_disabled",
                    "Posting is currently free - there is nothing to buy");
        }
        CreatorPackageProperties.Package config = creatorPackageService.configFor(code);
        PaymentProvider provider = paymentProviders.resolve(choice.method());

        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(creator)
                .type(PurchaseType.CREATOR_PACKAGE)
                .packageCode(code)
                .amountMinor(config.priceMinor())
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(provider.name())
                .build());

        return checkout(creator, purchase, provider, choice);
    }

    /**
     * Buying extra live minutes for today.
     *
     * <p>Exists because running out mid-broadcast otherwise means being cut off
     * with an audience watching. The minutes are added to today only and expire
     * with it - a creator who needs more every day wants a bigger package, and
     * selling her the same top-up daily instead would be worse value dressed up
     * as flexibility.
     *
     * <p>Granted on settlement rather than here: minutes nobody paid for must not
     * extend anything, and on mobile money the answer arrives minutes later.
     */
    @Transactional
    public CheckoutResponse buyLiveExtension(User creator, int minutes, PaymentChoice choice) {
        requireMonetisationOn();

        MonetizationProperties.LiveExtension rules = properties.liveExtension();
        if (rules == null || rules.pricePerMinuteMinor() <= 0) {
            throw ApiException.conflict("extensions_disabled",
                    "Buying extra live minutes is not available here.");
        }
        if (minutes <= 0 || minutes > rules.maxMinutesPerDay()) {
            throw ApiException.badRequest("bad_extension",
                    "You can buy between 1 and " + rules.maxMinutesPerDay() + " extra minutes a day.");
        }

        // A package is what the top-up tops up. Without one there is no allowance
        // to extend, and selling minutes to someone who cannot broadcast at all
        // would take money for nothing.
        if (creatorPackageService.dailyLiveMinutesFor(creator) <= 0) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                    "package_required",
                    "Going live needs a package. Choose one, then top up if you need longer.");
        }

        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC);
        PaymentProvider provider = paymentProviders.resolve(choice.method());

        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(creator)
                .type(PurchaseType.LIVE_EXTENSION)
                .extensionMinutes(minutes)
                .extensionDate(day)
                .amountMinor(rules.pricePerMinuteMinor() * minutes)
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(provider.name())
                .build());

        return checkout(creator, purchase, provider, choice);
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
    private CheckoutResponse checkout(User buyer, Purchase purchase,
                                      PaymentProvider provider, PaymentChoice choice) {
        // Set on every attempt, not only the first. A purchase left PENDING is
        // reused when the buyer tries again, and the second attempt may well be
        // from a different handset - the number recorded should be the one being
        // charged now, not the one that already failed.
        String normalised = normaliseMsisdn(choice.payerMsisdn());
        if (normalised != null) {
            purchase.setPayerMsisdn(normalised);
        }

        // The same reuse applies to the method itself: somebody whose Mobile Money
        // prompt went unanswered may come back and pay by card. Repointing the
        // purchase matters beyond bookkeeping - the reconcilers sweep by provider,
        // so a row left saying MOMO while Stripe holds the money would be chased by
        // MTN, found unknown, and failed underneath a payment that succeeded. The
        // old provider's reference goes with it, being meaningless to the new one.
        if (!provider.name().equals(purchase.getProvider())) {
            if (purchase.getProvider() != null) {
                log.info("Purchase {} switched from {} to {}",
                        purchase.getId(), purchase.getProvider(), provider.name());
            }
            purchase.setProvider(provider.name());
            purchase.setProviderReference(null);
        }

        // Never against a top-up. Paying for balance with balance is circular:
        // 5 000 of credit would cover a 5 000 top-up, settle it without a payment,
        // and hand back the 5 000 it just consumed - an infinite supply of money
        // for anybody who noticed. A top-up is only ever bought with real money.
        CreditService.Applied credit = purchase.getType() == PurchaseType.CREDIT_TOPUP
                ? new CreditService.Applied(0, purchase.getAmountMinor())
                : creditService.applyTo(buyer, purchase);
        purchase.setCreditAppliedMinor(credit.creditUsedMinor());

        if (credit.coversEverything()) {
            grant(purchase, "CREDIT-" + purchase.getId());
            return new CheckoutResponse(
                    PurchaseResponse.of(purchase),
                    PaymentProvider.PaymentInstruction.Action.NONE,
                    null,
                    null);
        }

        var instruction = provider.startPayment(purchase);
        if (instruction.reference() != null) {
            purchase.setProviderReference(instruction.reference());
        }

        // A provider that settles on the spot has nothing to wait for, so access
        // is granted before the response is written rather than leaving the client
        // polling a purchase that is never going to change.
        if (provider.settlesImmediately()) {
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
            // No earnings entry: nobody has earned anything yet. The creator's
            // share is recorded when a gift is actually sent, against whoever
            // received it - see GiftService.
            case CREDIT_TOPUP -> creditService.topUp(purchase.getUser(), purchase);
            case LIVE_EXTENSION -> liveQuotaService.grantMinutes(
                    purchase.getUser(),
                    purchase.getExtensionDate(),
                    purchase.getExtensionMinutes());
            // The bundle. Grants the same rows a per-item sale does and pays the
            // creator the same way, so nothing downstream needs to know that
            // four items arrived on one payment instead of four.
            case PROFILE_UNLOCK -> {
                if (purchase.getTargetUser() == null) {
                    // A row from before the bundle was reinstated, when this type
                    // meant a standing claim on a profile. Nothing to open.
                    log.warn("Purchase {} is a legacy PROFILE_UNLOCK with no target; nothing granted",
                            purchase.getId());
                } else {
                    int opened = grantBundle(purchase);
                    earningsService.recordItemEarning(purchase, purchase.getTargetUser());
                    notifyItemSold(purchase, purchase.getTargetUser(),
                            opened + (opened == 1 ? " item" : " items"));
                }
            }
            case SUBSCRIPTION -> log.warn(
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

    /**
     * Opens every item the bundle was sold with.
     *
     * <p>Works from the ids pinned to the purchase, never from the creator's
     * gallery as it stands now — settlement can be minutes after checkout on
     * mobile money, and the buyer is owed what they paid for rather than
     * whatever happens to be posted when the money lands.
     *
     * <p>Items already owned are skipped and items since deleted are ignored,
     * both without failing: a webhook that fires twice must not write two rows,
     * and a creator deleting one clip must not block access to the other three.
     *
     * @return how many were opened by this call
     */
    private int grantBundle(Purchase purchase) {
        UUID viewerId = purchase.getUser().getId();
        int opened = 0;

        for (UUID mediaId : purchase.getBundleMediaIds()) {
            if (mediaUnlockRepository.existsByViewerIdAndMediaId(viewerId, mediaId)) {
                continue;
            }
            var asset = mediaRepository.findById(mediaId).orElse(null);
            if (asset == null) {
                log.warn("Purchase {} covers media {} which no longer exists; skipped",
                        purchase.getId(), mediaId);
                continue;
            }
            mediaUnlockRepository.save(MediaUnlock.builder()
                    .viewer(purchase.getUser())
                    .media(asset)
                    .source(UnlockSource.PURCHASE)
                    .purchase(purchase)
                    .build());
            opened++;
        }

        log.info("Purchase {} opened {} of {} bundled items",
                purchase.getId(), opened, purchase.getBundleMediaIds().size());
        return opened;
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

    /** What the checkout picker shows, in configured order. */
    public java.util.List<com.nightgals.billing.dto.PaymentMethodResponse> paymentMethods() {
        var fallback = paymentProviders.defaultProvider();
        return paymentProviders.enabled().stream()
                .map(provider -> com.nightgals.billing.dto.PaymentMethodResponse.of(
                        provider, provider == fallback))
                .toList();
    }

    @Transactional(readOnly = true)
    public EntitlementResponse entitlements(User viewer) {
        long credit = creditService.balanceOf(viewer.getId());

        // The default method, not the only one - several are live at once now and
        // the buyer picks per checkout. Reported so a client that never renders a
        // picker still knows what it is about to get, and asked of the provider
        // itself rather than compared against "MOMO": which methods want a phone
        // number is the provider's business, and hard-coding it here would go
        // quietly wrong the moment a second mobile-money provider appears.
        //
        // GET /api/v1/billing/payment-methods is the fuller answer, and the one a
        // checkout screen should be built on.
        PaymentProvider fallback = paymentProviders.defaultProvider();

        return new EntitlementResponse(
                viewer.isOnTrial(),
                viewer.getTrialEndsAt(),
                mediaUnlockRepository.countByViewerId(viewer.getId()),
                credit,
                Money.plain(credit, properties.currency()),
                properties.currency(),
                fallback.name(),
                fallback.requiresPayerMsisdn());
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
