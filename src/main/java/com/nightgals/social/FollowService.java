package com.nightgals.social;

import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.social.dto.FollowedCreatorResponse;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Following a creator.
 *
 * <p>Exists to make reminders possible: a scheduled broadcast is only useful if
 * somebody is told about it, and there was nobody to tell.
 *
 * <p>Free, one-directional, and needing no approval. Following is a subscription
 * to somebody's schedule, not a relationship and not a purchase - it grants no
 * access to anything paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    @Transactional
    public void follow(User follower, UUID creatorId, boolean remind) {
        if (follower.getId().equals(creatorId)) {
            throw ApiException.badRequest("self_follow", "You already know what you are up to");
        }
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> ApiException.notFound("Creator"));
        if (!creator.isApproved()) {
            throw ApiException.notFound("Creator");
        }

        followRepository.findByFollowerIdAndCreatorId(follower.getId(), creatorId)
                .ifPresentOrElse(
                        existing -> existing.setRemind(remind),
                        () -> followRepository.save(Follow.builder()
                                .follower(follower)
                                .creator(creator)
                                .remind(remind)
                                .build()));
    }

    @Transactional
    public void unfollow(User follower, UUID creatorId) {
        followRepository.findByFollowerIdAndCreatorId(follower.getId(), creatorId)
                .ifPresent(followRepository::delete);
    }

    @Transactional(readOnly = true)
    public boolean follows(User follower, UUID creatorId) {
        return follower != null
                && followRepository.existsByFollowerIdAndCreatorId(follower.getId(), creatorId);
    }

    /** Which of these creators the caller follows, in one query for a whole page. */
    @Transactional(readOnly = true)
    public Set<UUID> followedAmong(User follower, List<UUID> creatorIds) {
        if (follower == null || creatorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(followRepository.findFollowedAmong(follower.getId(), creatorIds));
    }

    @Transactional(readOnly = true)
    public long followerCount(UUID creatorId) {
        return followRepository.countByCreatorId(creatorId);
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowedCreatorResponse> following(User follower, Pageable pageable) {
        return PageResponse.from(
                followRepository.findFollowing(follower.getId(), pageable),
                FollowedCreatorResponse::of);
    }

    /** Who to email about a creator's next broadcast. */
    @Transactional(readOnly = true)
    public List<Follow> remindableFollowersOf(UUID creatorId) {
        return followRepository.findRemindable(creatorId);
    }
}
