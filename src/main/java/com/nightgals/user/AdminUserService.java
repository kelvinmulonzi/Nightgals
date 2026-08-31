package com.nightgals.user;

import com.nightgals.auth.RefreshTokenRepository;
import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.user.dto.AdminUserResponse;
import com.nightgals.user.dto.SuspendUserRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Burning an account, and putting it back.
 *
 * <p>One switch, {@link UserStatus#SUSPENDED}, and everything follows from it:
 * sign-in is refused, live sessions stop, and every public listing drops the
 * account and its work. Nothing is deleted. A burn is a decision that has to be
 * reversible, because moderators act on incomplete information and the mistake
 * that cannot be undone is the one that costs a creator her audience.
 *
 * <p><b>Sessions are revoked, not left to expire.</b> An access token already in
 * a browser keeps working until it ages out, so without this a burned account
 * carries on posting for the rest of its token's life - which is precisely the
 * window a moderator was trying to close.
 *
 * <p>Two things an administrator may not do: burn themselves, and burn another
 * administrator. The first locks the last person out of the console with no way
 * back in; the second turns a disagreement between staff into a race.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(String query, UserStatus status, Pageable pageable) {
        return PageResponse.from(
                userRepository.search(query == null ? null : query.trim(), status, pageable),
                AdminUserResponse::of);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(UUID userId) {
        return AdminUserResponse.of(require(userId));
    }

    /** How many accounts sit in each state, for the console's header. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        return Map.of(
                "total", userRepository.count(),
                "active", userRepository.countByStatus(UserStatus.ACTIVE),
                "suspended", userRepository.countByStatus(UserStatus.SUSPENDED),
                "deactivated", userRepository.countByStatus(UserStatus.DEACTIVATED));
    }

    @Transactional
    public AdminUserResponse suspend(UUID userId, SuspendUserRequest request, User actor) {
        User target = require(userId);

        if (target.getId().equals(actor.getId())) {
            throw ApiException.conflict("self_suspend",
                    "You cannot burn your own account.");
        }
        if (target.getRole() == Role.ADMIN) {
            throw ApiException.conflict("admin_target",
                    "Administrators cannot be burned from the console. Change the role first.");
        }
        if (target.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.conflict("already_suspended", "That account is already burned.");
        }

        target.setStatus(UserStatus.SUSPENDED);
        target.setSuspendedAt(Instant.now());
        target.setSuspendedReason(request.reason().trim());
        target.setSuspendedById(actor.getId());
        User saved = userRepository.saveAndFlush(target);

        // Out of every browser it is signed into, now rather than whenever the
        // token happens to expire.
        int revoked = refreshTokenRepository.revokeAllForUser(saved.getId(), Instant.now());

        log.warn("Account {} burned by {} ({} sessions revoked): {}",
                saved.getId(), actor.getId(), revoked, saved.getSuspendedReason());
        return AdminUserResponse.of(saved);
    }

    @Transactional
    public AdminUserResponse restore(UUID userId, User actor) {
        User target = require(userId);

        if (target.getStatus() != UserStatus.SUSPENDED) {
            throw ApiException.conflict("not_suspended", "That account is not burned.");
        }

        target.setStatus(UserStatus.ACTIVE);
        // suspendedAt / reason / by are deliberately left standing. An account
        // that has been burned once before is exactly what a moderator wants to
        // know when it comes up a second time, and clearing the record on
        // restore is how that history quietly disappears.
        User saved = userRepository.saveAndFlush(target);

        log.warn("Account {} restored by {}", saved.getId(), actor.getId());
        return AdminUserResponse.of(saved);
    }

    private User require(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
    }
}
