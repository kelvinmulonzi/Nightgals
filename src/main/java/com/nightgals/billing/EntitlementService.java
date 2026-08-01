package com.nightgals.billing;

import com.nightgals.config.MonetizationProperties;
import com.nightgals.live.LiveSession;
import com.nightgals.media.MediaAsset;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single place that answers "may this viewer see that item?".
 *
 * <p>The unit is the item, not the creator. A viewer who bought one video owns
 * that video and nothing else - which is what "users can set their own unlock
 * price for every premium video they upload" means once it is followed through.
 *
 * <p>Six ways to be entitled, checked in this order because the cheap ones come
 * first and the query is last:
 *
 * <ol>
 *   <li>the item is FREE - the shop window, open to anyone including visitors
 *   <li>the caller is anonymous - never entitled to anything paid
 *   <li>monetisation is switched off entirely
 *   <li>it is their own item, or they are staff
 *   <li>their free trial is still running
 *   <li>they bought it
 * </ol>
 *
 * <p>Everything paid routes through here, so wiring in a real payment provider
 * changes nothing about access control - only how a {@link Purchase} reaches
 * COMPLETED.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final MediaUnlockRepository mediaUnlockRepository;
    private final LiveAccessRepository liveAccessRepository;
    private final MonetizationProperties properties;

    // ---------------------------------------------------------------- media

    @Transactional(readOnly = true)
    public boolean canView(User viewer, MediaAsset asset) {
        if (asset.isFree()) {
            return true;
        }
        Boolean shortcut = shortcut(viewer, asset.getUser().getId());
        if (shortcut != null) {
            return shortcut;
        }
        return mediaUnlockRepository.existsByViewerIdAndMediaId(viewer.getId(), asset.getId());
    }

    /**
     * The same question for a whole gallery, in one query instead of one per tile.
     *
     * @return the ids the viewer may see, free items included - so a caller can
     *         treat the result as the whole answer rather than merging two lists
     */
    @Transactional(readOnly = true)
    public Set<UUID> viewableAmong(User viewer, List<MediaAsset> assets) {
        Set<UUID> free = assets.stream()
                .filter(MediaAsset::isFree)
                .map(MediaAsset::getId)
                .collect(Collectors.toSet());

        List<MediaAsset> paid = assets.stream().filter(a -> !a.isFree()).toList();
        if (paid.isEmpty() || viewer == null) {
            return free;
        }
        if (!properties.enabled() || viewer.isStaff() || viewer.isOnTrial()) {
            return assets.stream().map(MediaAsset::getId).collect(Collectors.toSet());
        }

        Set<UUID> result = new HashSet<>(free);
        List<UUID> toQuery = new ArrayList<>();
        for (MediaAsset asset : paid) {
            if (asset.getUser().getId().equals(viewer.getId())) {
                result.add(asset.getId());
            } else {
                toQuery.add(asset.getId());
            }
        }
        if (!toQuery.isEmpty()) {
            result.addAll(mediaUnlockRepository.findUnlockedAmong(viewer.getId(), toQuery));
        }
        return result;
    }

    // ---------------------------------------------------------------- live

    @Transactional(readOnly = true)
    public boolean canJoin(User viewer, LiveSession session) {
        if (session.isFree()) {
            return true;
        }
        Boolean shortcut = shortcut(viewer, session.getHost().getId());
        if (shortcut != null) {
            return shortcut;
        }
        return liveAccessRepository.existsByViewerIdAndSessionId(viewer.getId(), session.getId());
    }

    @Transactional(readOnly = true)
    public Set<UUID> joinableAmong(User viewer, List<LiveSession> sessions) {
        Set<UUID> open = sessions.stream()
                .filter(LiveSession::isFree)
                .map(LiveSession::getId)
                .collect(Collectors.toSet());

        List<LiveSession> paid = sessions.stream().filter(s -> !s.isFree()).toList();
        if (paid.isEmpty() || viewer == null) {
            return open;
        }
        if (!properties.enabled() || viewer.isStaff() || viewer.isOnTrial()) {
            return sessions.stream().map(LiveSession::getId).collect(Collectors.toSet());
        }

        Set<UUID> result = new HashSet<>(open);
        List<UUID> toQuery = new ArrayList<>();
        for (LiveSession session : paid) {
            if (session.getHost().getId().equals(viewer.getId())) {
                result.add(session.getId());
            } else {
                toQuery.add(session.getId());
            }
        }
        if (!toQuery.isEmpty()) {
            result.addAll(liveAccessRepository.findAccessibleAmong(viewer.getId(), toQuery));
        }
        return result;
    }

    // ---------------------------------------------------------------- shared

    /**
     * The answers that do not depend on which item is being asked about.
     *
     * @return TRUE or FALSE when the question is already settled, null when it
     *         comes down to whether this particular item was bought
     */
    private Boolean shortcut(User viewer, UUID ownerId) {
        // Anonymous callers hold nothing, and this is checked before the
        // monetisation switch: turning the paywall off must open content to
        // members, not to the open internet.
        if (viewer == null) {
            return false;
        }
        if (!properties.enabled()) {
            return true;
        }
        // Your own content, and staff doing moderation, are never paywalled.
        if (viewer.getId().equals(ownerId) || viewer.isStaff()) {
            return true;
        }
        // The seven free days open everything, which is the whole point of them.
        if (viewer.isOnTrial()) {
            return true;
        }
        return null;
    }

    public boolean monetisationEnabled() {
        return properties.enabled();
    }
}
