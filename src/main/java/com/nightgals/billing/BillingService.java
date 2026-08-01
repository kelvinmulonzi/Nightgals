package com.nightgals.billing;

import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.EntitlementResponse;
import com.nightgals.billing.dto.PlanResponse;
import com.nightgals.billing.dto.PurchaseResponse;
import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.config.CreatorPackageProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.earnings.EarningsService;
import com.nightgals.mail.EmailService;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creating purchases and turning settled ones into access.
 *
 * <p>{@link #settle} is the seam a real payment provider plugs into: a webhook,
 * an STK-push callback or an admin confirmation all end up calling it, and only
 * it grants entitlements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final PurchaseRepository purchaseRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ProfileUnlockRepository unlockRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final EntitlementService entitlementService;
    private final CreatorPackageService creatorPackageService;
    private final PaymentProvider paymentProvider;
    private final EarningsService earningsService;
    private final EmailService emailService;
    private final MonetizationProperties properties;

    // ---------------------------------------------------------------- catalogue

    public PlanResponse plans() {
        MonetizationProperties.ProfileUnlock unlock = properties.profileUnlock();

        List<PlanResponse.PlanOption> subscriptions = properties.plans() == null ? List.of()
                : properties.plans().entrySet().stream()
                        .map(e -> new PlanResponse.PlanOption(
                                e.getKey(),
                                e.getValue().label(),
                                e.getValue().priceMinor(),
                                money(e.getValue().priceMinor()),
                                e.getValue().duration()))
                        .sorted(Comparator.comparingLong(PlanResponse.PlanOption::priceMinor))
                        .toList();

        return new PlanResponse(
                properties.enabled(),
                properties.currency(),
                unlock == null ? null
                        : new PlanResponse.UnlockOption(unlock.priceMinor(), money(unlock.priceMinor()),
                                unlock.duration()),
                subscriptions);
    }

    // ---------------------------------------------------------------- buying

    /**
     * What it costs to unlock one creator.
     *
     * <p>Her own price if she has set one, otherwise the platform default. Read on
     * every profile view and every card, so it is a single indexed lookup.
     */
    @Transactional(readOnly = true)
    public long unlockPriceFor(UUID creatorId) {
        return profileRepository.findByUserId(creatorId)
                .map(profile -> profile.getUnlockPriceMinor())
                .filter(java.util.Objects::nonNull)
                .orElse(properties.profileUnlock().priceMinor());
    }

    @Transactional
    public CheckoutResponse unlockProfile(User buyer, UUID targetUserId) {
        requireMonetisationOn();

        if (buyer.getId().equals(targetUserId)) {
            throw ApiException.badRequest("self_unlock", "You do not need to unlock your own profile");
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> ApiException.notFound("Member"));
        if (!target.isApproved()) {
            throw ApiException.notFound("Member");
        }
        if (entitlementService.canViewPremium(buyer, targetUserId)) {
            throw ApiException.conflict("already_unlocked",
                    "You already have access to this member's content");
        }

        // Reuse a payment the buyer already started rather than creating a second
        // one. The price is read once, when the purchase is opened: a creator who
        // changes hers mid-checkout must not change what somebody is being charged.
        Purchase purchase = purchaseRepository.findPendingUnlock(buyer.getId(), targetUserId)
                .orElseGet(() -> purchaseRepository.save(Purchase.builder()
                        .user(buyer)
                        .type(PurchaseType.PROFILE_UNLOCK)
                        .targetUser(target)
                        .amountMinor(unlockPriceFor(targetUserId))
                        .currency(properties.currency())
                        .status(PurchaseStatus.PENDING)
                        .provider(paymentProvider.name())
                        .build()));

        return checkout(purchase);
    }

    /**
     * A creator buying the right to publish.
     *
     * <p>Not gated on identity verification: letting somebody pay while their
     * documents are in review means the package is live the moment they are
     * approved, rather than adding a second wait to the end of the first.
     */
    @Transactional
    public CheckoutResponse buyCreatorPackage(User creator, String packageCode) {
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

        return checkout(purchase);
    }

    @Transactional
    public CheckoutResponse subscribe(User buyer, String planCode) {
        requireMonetisationOn();

        Map<String, MonetizationProperties.Plan> plans = properties.plans();
        MonetizationProperties.Plan plan = plans == null ? null : plans.get(planCode);
        if (plan == null) {
            throw ApiException.badRequest("unknown_plan",
                    "No such plan. Valid codes: " + (plans == null ? "(none configured)" : plans.keySet()));
        }

        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .user(buyer)
                .type(PurchaseType.SUBSCRIPTION)
                .planCode(planCode)
                .amountMinor(plan.priceMinor())
                .currency(properties.currency())
                .status(PurchaseStatus.PENDING)
                .provider(paymentProvider.name())
                .build());

        return checkout(purchase);
    }

    private CheckoutResponse checkout(Purchase purchase) {
        var instruction = paymentProvider.startPayment(purchase);
        if (instruction.reference() != null) {
            purchase.setProviderReference(instruction.reference());
        }

        // A provider that settles on the spot has nothing to wait for, so access
        // is granted before the response is written rather than leaving the client
        // to poll a purchase that is never going to change.
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
     * webhook that fires twice cannot grant two subscriptions.
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
     * <p>Shared by the two ways a purchase reaches COMPLETED - an out-of-band
     * settlement arriving at {@link #settle}, and a provider that settles the
     * moment the purchase is created - so neither route can drift from the other
     * on what access is granted or who gets paid.
     */
    private void grant(Purchase purchase, String providerReference) {
        purchase.setStatus(PurchaseStatus.COMPLETED);
        purchase.setCompletedAt(Instant.now());
        if (providerReference != null && !providerReference.isBlank()) {
            purchase.setProviderReference(providerReference);
        }

        switch (purchase.getType()) {
            case PROFILE_UNLOCK -> {
                grantUnlock(purchase);
                // The creator is credited immediately; a subscription's share is
                // only known once the period's viewing is in, so it is attributed
                // later by EarningsService.distributeSubscriptionRevenue.
                earningsService.recordUnlockEarning(purchase);
                notifyUnlock(purchase);
            }
            case SUBSCRIPTION -> grantSubscription(purchase);
            // Money towards the platform, not towards a creator, so deliberately
            // no earnings entry.
            case CREATOR_PACKAGE -> grantCreatorPackage(purchase);
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
        return PurchaseResponse.of(purchase);
    }

    private void grantUnlock(Purchase purchase) {
        var duration = properties.profileUnlock().duration();
        Instant expiresAt = duration == null ? null : Instant.now().plus(duration);

        unlockRepository.findByViewerIdAndTargetId(
                        purchase.getUser().getId(), purchase.getTargetUser().getId())
                .ifPresentOrElse(
                        // Buying again extends rather than duplicating.
                        existing -> existing.setExpiresAt(expiresAt),
                        () -> unlockRepository.save(ProfileUnlock.builder()
                                .viewer(purchase.getUser())
                                .target(purchase.getTargetUser())
                                .source(UnlockSource.PURCHASE)
                                .expiresAt(expiresAt)
                                .purchase(purchase)
                                .build()));
    }

    private void grantCreatorPackage(Purchase purchase) {
        CreatorPackage granted = creatorPackageService.grant(
                purchase.getUser(), purchase.getPackageCode(), purchase);
        CreatorPackageProperties.Package config =
                creatorPackageService.configFor(purchase.getPackageCode());

        emailService.sendPackageReceipt(
                purchase.getUser().getEmail(),
                purchase.getUser().getUsername(),
                config.label(),
                purchase.getCurrency() + " " + money(purchase.getAmountMinor()),
                granted.getExpiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                config.maxPhotos(),
                config.maxVideos());
    }

    /**
     * Tells both sides that an unlock went through.
     *
     * <p>Best-effort by design - {@link EmailService} sends these asynchronously
     * and swallows failures, because a receipt that does not arrive must never
     * roll back a payment that did.
     */
    private void notifyUnlock(Purchase purchase) {
        User buyer = purchase.getUser();
        User creator = purchase.getTargetUser();
        String amount = purchase.getCurrency() + " " + money(purchase.getAmountMinor());

        String accessUntil = unlockRepository
                .findByViewerIdAndTargetId(buyer.getId(), creator.getId())
                .map(ProfileUnlock::getExpiresAt)
                .map(expiry -> expiry.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                .orElse("no expiry");

        emailService.sendUnlockReceipt(buyer.getEmail(), creator.getUsername(), amount, accessUntil);
        emailService.sendNewFanNotice(creator.getEmail(), creator.getUsername(), amount);
    }

    private void grantSubscription(Purchase purchase) {
        MonetizationProperties.Plan plan = properties.plans().get(purchase.getPlanCode());
        Instant now = Instant.now();

        // Renewing while still active extends from the current expiry, so nobody
        // loses days by paying early.
        Instant startsAt = subscriptionRepository.findActive(purchase.getUser().getId(), now)
                .map(Subscription::getExpiresAt)
                .orElse(now);

        subscriptionRepository.save(Subscription.builder()
                .user(purchase.getUser())
                .planCode(purchase.getPlanCode())
                .startsAt(startsAt)
                .expiresAt(startsAt.plus(plan.duration()))
                .purchase(purchase)
                .build());
    }

    /** Staff comp: access without payment, recorded as a GRANT. */
    @Transactional
    public void grantUnlock(UUID viewerId, UUID targetId, Instant expiresAt) {
        User viewer = userRepository.findById(viewerId).orElseThrow(() -> ApiException.notFound("Viewer"));
        User target = userRepository.findById(targetId).orElseThrow(() -> ApiException.notFound("Target"));

        unlockRepository.findByViewerIdAndTargetId(viewerId, targetId)
                .ifPresentOrElse(
                        existing -> existing.setExpiresAt(expiresAt),
                        () -> unlockRepository.save(ProfileUnlock.builder()
                                .viewer(viewer)
                                .target(target)
                                .source(UnlockSource.GRANT)
                                .expiresAt(expiresAt)
                                .build()));
        log.info("Granted unlock of {} to {}", targetId, viewerId);
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public EntitlementResponse entitlements(User viewer) {
        var subscription = subscriptionRepository.findActive(viewer.getId(), Instant.now());

        var unlocked = unlockRepository
                .findActiveForViewer(viewer.getId(), Instant.now(), PageRequest.of(0, 200))
                .getContent().stream()
                .map(u -> new EntitlementResponse.UnlockedMember(
                        u.getTarget().getId(), u.getTarget().getUsername(), u.getExpiresAt()))
                .toList();

        return new EntitlementResponse(
                subscription.isPresent(),
                subscription.map(Subscription::getPlanCode).orElse(null),
                subscription.map(Subscription::getExpiresAt).orElse(null),
                unlocked);
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

    private String money(long minor) {
        return String.format("%.2f", minor / 100.0);
    }
}
