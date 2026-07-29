package com.nightgals.live;

import com.nightgals.billing.EntitlementService;
import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.earnings.EarningsService;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.live.dto.LiveSessionResponse;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSessionService {

    private final LiveSessionRepository sessionRepository;
    private final EntitlementService entitlementService;
    private final EarningsService earningsService;

    /** Only verified members may broadcast - same gate as posting media. */
    @Transactional
    public LiveSessionResponse create(User host, LiveSessionRequest request) {
        if (!host.isApproved()) {
            throw ApiException.forbidden("verification_required",
                    "Verify your identity before going live");
        }
        sessionRepository.findFirstByHostIdAndStatus(host.getId(), LiveStatus.LIVE)
                .ifPresent(existing -> {
                    throw ApiException.conflict("already_live", "You are already broadcasting");
                });

        LiveSession session = LiveSession.builder()
                .host(host)
                .title(request.title().trim())
                .playbackUrl(request.playbackUrl())
                .scheduledFor(request.scheduledFor())
                .tier(request.tier() == null
                        ? com.nightgals.media.ContentTier.EXCLUSIVE : request.tier())
                .status(request.scheduledFor() == null ? LiveStatus.LIVE : LiveStatus.SCHEDULED)
                .startedAt(request.scheduledFor() == null ? Instant.now() : null)
                .build();

        log.info("Live session created by {} ({})", host.getId(), session.getStatus());
        return LiveSessionResponse.of(sessionRepository.save(session), true);
    }

    @Transactional
    public LiveSessionResponse start(User host, UUID sessionId) {
        LiveSession session = requireOwned(host, sessionId);
        if (session.getStatus() == LiveStatus.ENDED || session.getStatus() == LiveStatus.CANCELLED) {
            throw ApiException.conflict("already_finished", "This session has already finished");
        }
        session.setStatus(LiveStatus.LIVE);
        session.setStartedAt(Instant.now());
        return LiveSessionResponse.of(session, true);
    }

    @Transactional
    public LiveSessionResponse end(User host, UUID sessionId) {
        LiveSession session = requireOwned(host, sessionId);
        session.setStatus(LiveStatus.ENDED);
        session.setEndedAt(Instant.now());
        return LiveSessionResponse.of(session, true);
    }

    /**
     * Everyone currently broadcasting. Public - the listing is the advert. Playback
     * URLs are withheld unless the viewer has unlocked that host.
     *
     * @param viewer the caller, or null when anonymous
     */
    @Transactional(readOnly = true)
    public PageResponse<LiveSessionResponse> live(User viewer, Pageable pageable) {
        var page = sessionRepository.findLive(pageable);

        var hostIds = page.getContent().stream().map(s -> s.getHost().getId()).distinct().toList();
        var unlocked = entitlementService.unlockedAmong(viewer, hostIds);

        return PageResponse.from(page, s -> LiveSessionResponse.of(s, unlocked.contains(s.getHost().getId())));
    }

    @Transactional(readOnly = true)
    public List<LiveSessionResponse> forHost(UUID hostId, User viewer) {
        boolean unlocked = entitlementService.canViewPremium(viewer, hostId);
        return sessionRepository.findByHostIdOrderByCreatedAtDesc(hostId).stream()
                .map(s -> LiveSessionResponse.of(s, unlocked))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LiveSessionResponse> mine(UUID hostId) {
        return sessionRepository.findByHostIdOrderByCreatedAtDesc(hostId).stream()
                .map(s -> LiveSessionResponse.of(s, true))
                .toList();
    }

    /**
     * The playback URL on its own, for a client that already has the session and
     * wants to join. Fails with 402 rather than silently returning null, so the
     * client can open the paywall.
     */
    @Transactional(readOnly = true)
    public String playbackUrl(UUID sessionId, User viewer) {
        LiveSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Live session"));

        if (!session.isFree()
                && !entitlementService.canViewPremium(viewer, session.getHost().getId())) {
            throw viewer == null
                    ? ApiException.unauthorized("Sign in to watch this session")
                    : ApiException.paymentRequired("Unlock this creator to watch their live sessions");
        }
        if (session.getPlaybackUrl() == null) {
            throw ApiException.notFound("Playback URL");
        }
        if (!session.isFree()) {
            earningsService.recordPremiumView(viewer, session.getHost().getId());
        }
        return session.getPlaybackUrl();
    }

    private LiveSession requireOwned(User host, UUID sessionId) {
        LiveSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Live session"));
        if (!session.getHost().getId().equals(host.getId())) {
            throw ApiException.notFound("Live session");
        }
        return session;
    }
}
