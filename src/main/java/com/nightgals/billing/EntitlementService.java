package com.nightgals.billing;

import com.nightgals.config.MonetizationProperties;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single place that answers "can this viewer see that member's premium
 * content?".
 *
 * <p>Everything paid routes through here, so when a real payment provider is
 * wired in nothing about access control has to change - only how a
 * {@link Purchase} reaches COMPLETED.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;
    private final ProfileUnlockRepository unlockRepository;
    private final MonetizationProperties properties;

    /**
     * Premium content is photos beyond the free preview, all video, and live
     * sessions.
     */
    @Transactional(readOnly = true)
    public boolean canViewPremium(User viewer, UUID targetUserId) {
        // Anonymous callers hold nothing, and this is checked before the
        // monetisation switch: turning the paywall off must open content to
        // members, not to the open internet.
        if (viewer == null) {
            return false;
        }
        // Monetisation off: nothing is gated.
        if (!properties.enabled()) {
            return true;
        }
        // Your own content, and staff doing moderation, are never paywalled.
        if (viewer.getId().equals(targetUserId) || viewer.isStaff()) {
            return true;
        }
        Instant now = Instant.now();
        if (subscriptionRepository.findActive(viewer.getId(), now).isPresent()) {
            return true;
        }
        return unlockRepository.hasActiveUnlock(viewer.getId(), targetUserId, now);
    }

    /**
     * The same question for a whole page of the feed, in two queries instead of
     * two per card.
     */
    @Transactional(readOnly = true)
    public Set<UUID> unlockedAmong(User viewer, List<UUID> targetUserIds) {
        if (targetUserIds.isEmpty() || viewer == null) {
            return Set.of();
        }
        if (!properties.enabled() || viewer.isStaff() || hasActiveSubscription(viewer)) {
            return Set.copyOf(targetUserIds);
        }
        return Set.copyOf(unlockRepository.findUnlockedTargetIds(
                viewer.getId(), targetUserIds, Instant.now()));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSubscription(User viewer) {
        return subscriptionRepository.findActive(viewer.getId(), Instant.now()).isPresent();
    }

    public boolean monetisationEnabled() {
        return properties.enabled();
    }
}
