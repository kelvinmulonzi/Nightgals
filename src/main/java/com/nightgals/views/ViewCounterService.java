package com.nightgals.views;

import com.nightgals.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Counting who looked at what.
 *
 * <p>Two rules, and both matter more than the counting itself:
 *
 * <p><b>Once per person per day.</b> Otherwise a refresh is a view, a creator
 * admiring her own profile is a popular creator, and the number on the page is
 * a measure of how often something was rendered rather than how many people
 * cared. The uniqueness is enforced by an index, not by this class - see
 * {@link ContentViewRepository#recordOnce}.
 *
 * <p><b>A failure here must never break the page.</b> Recording a view is
 * bookkeeping attached to a read; a reader who is shown an error because the
 * counter could not be written has been failed for no reason at all. So this
 * runs in its own transaction and swallows everything: at worst a view goes
 * uncounted, which is a rounding error, rather than a profile failing to load,
 * which is an outage.
 *
 * <p><b>The owner does not count.</b> Nobody's view of their own work is
 * audience, and letting it count is the first thing anybody would notice.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ViewCounterService {

    private final ContentViewRepository repository;

    /**
     * Records a look and bumps the counter, if this is the first time today.
     *
     * @param ownerId whose item this is, so their own visits are not counted.
     *                Null when the subject has no owner worth excluding.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ViewSubject subject, UUID subjectId, User viewer, UUID ownerId,
                       HttpServletRequest request) {
        try {
            if (subjectId == null) {
                return;
            }
            if (viewer != null && ownerId != null && viewer.getId().equals(ownerId)) {
                return;
            }
            // Staff are working, not browsing. A moderation queue would otherwise
            // put the most-reviewed accounts at the top of "most viewed".
            if (viewer != null && viewer.isStaff()) {
                return;
            }

            String key = viewerKey(viewer, request);
            if (key == null) {
                return;
            }

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            int inserted = repository.recordOnce(
                    subject.name(), subjectId, key, viewer == null ? null : viewer.getId(), today);
            if (inserted == 0) {
                // Seen already today. The ledger says so, so the counter must not move.
                return;
            }

            switch (subject) {
                case PROFILE -> repository.bumpProfile(subjectId);
                case MEDIA -> repository.bumpMedia(subjectId);
                case REEL -> repository.bumpReel(subjectId);
            }
        } catch (RuntimeException e) {
            // Deliberately swallowed. See the class comment: this is bookkeeping
            // hanging off somebody else's page load, and it does not get to fail it.
            log.debug("View not counted for {} {}: {}", subject, subjectId, e.toString());
        }
    }

    /**
     * Who this is, for the purpose of "have they already been counted today".
     *
     * <p>Signed in, it is the account. Signed out, it is a SHA-256 of the address
     * and the user agent - which separates two visitors well enough for a day
     * without storing anything that identifies either, and which stops meaning
     * anything the moment that day's rows age out.
     *
     * <p>Null when there is nothing to key on at all, which is the honest answer:
     * a view that cannot be attributed to anybody cannot be deduplicated, and
     * counting it would make the number worse rather than better.
     */
    private String viewerKey(User viewer, HttpServletRequest request) {
        if (viewer != null) {
            return "u:" + viewer.getId();
        }
        if (request == null) {
            return null;
        }
        String address = clientIp(request);
        String agent = request.getHeader("User-Agent");
        if (address == null || address.isBlank()) {
            return null;
        }
        return "a:" + sha256(address + "|" + (agent == null ? "" : agent));
    }

    /** Prefers the proxy header, since the app sits behind one in every real deployment. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            // 62 characters, inside the column's 64.
            return HexFormat.of().formatHex(hash).substring(0, 60);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not optional in a JRE", e);
        }
    }
}
