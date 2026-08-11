package com.nightgals.live;

import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.ItemPricingService;
import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.live.dto.LiveSessionResponse;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Broadcasts: scheduling them, running them, and deciding who may watch.
 *
 * <p>Three things layer on top of a plain session:
 *
 * <ul>
 *   <li>a <b>price</b> - each stream can have its own, and it is bought per
 *       stream rather than per creator
 *   <li>a <b>daily allowance</b> - 15 minutes on Pro, 45 on Diamond, 2 hours on
 *       Black Diamond, metered by {@link LiveQuotaService}
 *   <li>a <b>roster</b> - a session can be co-hosted, which is what "live
 *       sessions with multiple participants or hosts" means
 * </ul>
 *
 * <p>The application still does not ingest, transcode or serve video.
 * {@code playbackUrl} points at whatever provider the host is using; all this
 * does is record that the session exists and decide who gets the URL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSessionService {

    private final LiveSessionRepository sessionRepository;
    private final LiveHostRepository hostRepository;
    private final UserRepository userRepository;
    private final EntitlementService entitlementService;
    private final LiveQuotaService quotaService;
    private final ItemPricingService pricing;

    // ---------------------------------------------------------------- creating

    /**
     * Schedules a broadcast, or starts one now.
     *
     * <p>{@code scheduledFor} is what separates the two: with it, the session goes
     * in the calendar and its followers are reminded; without it, she is live as
     * soon as this returns.
     */
    @Transactional
    public LiveSessionResponse create(User host, LiveSessionRequest request) {
        if (!host.isApproved()) {
            throw ApiException.forbidden("verification_required",
                    "Verify your identity before going live");
        }

        boolean startingNow = request.scheduledFor() == null;
        if (startingNow) {
            // Only checked for a broadcast starting now: a session scheduled for
            // next week cannot be metered against an allowance that resets daily.
            quotaService.requireCanBroadcast(host);
            sessionRepository.findFirstByHostIdAndStatus(host.getId(), LiveStatus.LIVE)
                    .ifPresent(existing -> {
                        throw ApiException.conflict("already_live", "You are already broadcasting");
                    });
        } else if (request.scheduledFor().isBefore(Instant.now())) {
            throw ApiException.badRequest("scheduled_in_past",
                    "That time has already passed. Pick a later one.");
        }
        quotaService.requireDurationFits(host, request.durationMinutes());

        // Free unless she asks otherwise. Broadcasts earn through gifts sent while
        // they run rather than a door charge, and a paywall in front of the stream
        // works against that: nobody gifts a creator they never got to watch.
        var tier = request.tier() == null
                ? com.nightgals.media.ContentTier.FREE : request.tier();

        LiveSession session = sessionRepository.save(LiveSession.builder()
                .host(host)
                .title(request.title().trim())
                .playbackUrl(request.playbackUrl())
                .scheduledFor(request.scheduledFor())
                .durationMinutes(request.durationMinutes())
                .tier(tier)
                // A free broadcast is never priced; an exclusive one falls back to
                // the platform default until she names her own.
                .accessPriceMinor(tier == com.nightgals.media.ContentTier.FREE
                        ? null : pricing.validate(request.accessPriceMinor()))
                .status(startingNow ? LiveStatus.LIVE : LiveStatus.SCHEDULED)
                .startedAt(startingNow ? Instant.now() : null)
                .build());

        // The owner goes on the roster immediately, so it is complete from the
        // start rather than empty until somebody invites a co-host.
        hostRepository.save(LiveHost.builder()
                .session(session)
                .user(host)
                .role(HostRole.OWNER)
                .status(HostStatus.ACCEPTED)
                .respondedAt(Instant.now())
                .build());

        log.info("Live session created by {} ({})", host.getId(), session.getStatus());
        return describeForOwner(session);
    }

    @Transactional
    public LiveSessionResponse update(User host, UUID sessionId, LiveSessionRequest request) {
        LiveSession session = requireOwned(host, sessionId);
        if (session.getStatus() == LiveStatus.ENDED) {
            throw ApiException.conflict("already_finished", "This session has already finished");
        }
        quotaService.requireDurationFits(host, request.durationMinutes());

        session.setTitle(request.title().trim());
        session.setPlaybackUrl(request.playbackUrl());
        if (request.scheduledFor() != null) {
            session.setScheduledFor(request.scheduledFor());
            // Moving a broadcast means its followers have to be told again.
            session.setReminderSentAt(null);
        }
        session.setDurationMinutes(request.durationMinutes());
        if (request.tier() != null) {
            session.setTier(request.tier());
        }
        if (session.isFree()) {
            session.setAccessPriceMinor(null);
        } else if (request.accessPriceMinor() != null) {
            session.setAccessPriceMinor(pricing.validate(request.accessPriceMinor()));
        }
        return describeForOwner(session);
    }

    @Transactional
    public LiveSessionResponse start(User host, UUID sessionId) {
        LiveSession session = requireOwned(host, sessionId);
        if (session.getStatus() == LiveStatus.ENDED || session.getStatus() == LiveStatus.CANCELLED) {
            throw ApiException.conflict("already_finished", "This session has already finished");
        }
        quotaService.requireCanBroadcast(host);

        session.setStatus(LiveStatus.LIVE);
        session.setStartedAt(Instant.now());
        return describeForOwner(session);
    }

    @Transactional
    public LiveSessionResponse end(User host, UUID sessionId) {
        LiveSession session = requireOwned(host, sessionId);
        session.setStatus(LiveStatus.ENDED);
        session.setEndedAt(Instant.now());

        // Booked now, because now is when the length is known.
        quotaService.record(session);
        return describeForOwner(session);
    }

    // ---------------------------------------------------------------- roster

    /**
     * Invites somebody to co-host.
     *
     * <p>The session keeps one owner. A co-host appears in it and spends none of
     * their own daily allowance - the minutes come off the owner's, because the
     * owner is who the session belongs to and who earns from it.
     */
    @Transactional
    public void invite(User owner, UUID sessionId, UUID inviteeId) {
        LiveSession session = requireOwned(owner, sessionId);
        if (inviteeId.equals(owner.getId())) {
            throw ApiException.badRequest("self_invite", "You are already hosting this");
        }
        User invitee = userRepository.findById(inviteeId)
                .orElseThrow(() -> ApiException.notFound("Member"));
        if (!invitee.isApproved()) {
            throw ApiException.badRequest("not_verified",
                    "Only verified members can appear in a broadcast");
        }

        hostRepository.findBySessionIdAndUserId(sessionId, inviteeId)
                .ifPresentOrElse(existing -> {
                    // Re-inviting somebody who declined or was removed reopens it
                    // rather than failing.
                    existing.setStatus(HostStatus.INVITED);
                    existing.setRespondedAt(null);
                }, () -> hostRepository.save(LiveHost.builder()
                        .session(session)
                        .user(invitee)
                        .role(HostRole.CO_HOST)
                        .status(HostStatus.INVITED)
                        .build()));

        log.info("{} invited {} to co-host {}", owner.getId(), inviteeId, sessionId);
    }

    @Transactional
    public void respondToInvite(User invitee, UUID sessionId, boolean accept) {
        LiveHost entry = hostRepository.findBySessionIdAndUserId(sessionId, invitee.getId())
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (entry.getRole() == HostRole.OWNER) {
            throw ApiException.badRequest("owner_cannot_respond", "You own this session");
        }
        entry.setStatus(accept ? HostStatus.ACCEPTED : HostStatus.DECLINED);
        entry.setRespondedAt(Instant.now());
    }

    @Transactional
    public void removeHost(User owner, UUID sessionId, UUID hostUserId) {
        requireOwned(owner, sessionId);
        LiveHost entry = hostRepository.findBySessionIdAndUserId(sessionId, hostUserId)
                .orElseThrow(() -> ApiException.notFound("Host"));
        if (entry.getRole() == HostRole.OWNER) {
            throw ApiException.badRequest("cannot_remove_owner",
                    "The owner cannot be removed from their own session");
        }
        entry.setStatus(HostStatus.REMOVED);
    }

    /** What this creator has been asked to co-host and not yet answered. */
    @Transactional(readOnly = true)
    public List<LiveSessionResponse> pendingInvites(User user) {
        return hostRepository.findByUser(user.getId(), HostStatus.INVITED).stream()
                .map(h -> LiveSessionResponse.of(h.getSession(), false, List.of(),
                        priceOf(h.getSession())))
                .toList();
    }

    // ---------------------------------------------------------------- reading

    /**
     * Everyone currently broadcasting. Public - the listing is the advert.
     * Playback URLs are withheld from anyone who has not bought that stream.
     */
    @Transactional(readOnly = true)
    public PageResponse<LiveSessionResponse> live(User viewer, Pageable pageable) {
        var page = sessionRepository.findLive(pageable);
        return PageResponse.from(page, decorator(viewer, page.getContent()));
    }

    /** The calendar: what is coming up, soonest first. */
    @Transactional(readOnly = true)
    public PageResponse<LiveSessionResponse> upcoming(User viewer, Pageable pageable) {
        var page = sessionRepository.findUpcoming(Instant.now(), pageable);
        return PageResponse.from(page, decorator(viewer, page.getContent()));
    }

    @Transactional(readOnly = true)
    public List<LiveSessionResponse> forHost(UUID hostId, User viewer) {
        List<LiveSession> sessions = sessionRepository.findByHostIdOrderByCreatedAtDesc(hostId);
        var decorate = decorator(viewer, sessions);
        return sessions.stream().map(decorate).toList();
    }

    @Transactional(readOnly = true)
    public List<LiveSessionResponse> mine(UUID hostId) {
        return sessionRepository.findByHostIdOrderByCreatedAtDesc(hostId).stream()
                .map(this::describeForOwner)
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

        // A co-host gets in without paying for the session they are appearing in.
        boolean onRoster = viewer != null && hostRepository
                .findBySessionIdAndUserId(sessionId, viewer.getId())
                .filter(LiveHost::isOnAir)
                .isPresent();

        if (!onRoster && !entitlementService.canJoin(viewer, session)) {
            throw viewer == null
                    ? ApiException.unauthorized("Sign in to watch this session")
                    : ApiException.paymentRequired("Buy access to this broadcast to watch it");
        }
        if (session.getPlaybackUrl() == null) {
            throw ApiException.notFound("Playback URL");
        }
        return session.getPlaybackUrl();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Builds the per-session view for a page of results.
     *
     * <p>Access and rosters are resolved for the whole page in two queries rather
     * than two per row.
     */
    private Function<LiveSession, LiveSessionResponse> decorator(User viewer, List<LiveSession> sessions) {
        if (sessions.isEmpty()) {
            return session -> null;
        }
        Set<UUID> joinable = entitlementService.joinableAmong(viewer, sessions);
        Map<UUID, List<String>> rosters = rostersFor(sessions);

        return session -> LiveSessionResponse.of(
                session,
                joinable.contains(session.getId()),
                rosters.getOrDefault(session.getId(), List.of()),
                priceOf(session));
    }

    private Map<UUID, List<String>> rostersFor(List<LiveSession> sessions) {
        List<UUID> ids = sessions.stream().map(LiveSession::getId).toList();
        return hostRepository.findAcceptedForSessions(ids).stream()
                .collect(Collectors.groupingBy(
                        h -> h.getSession().getId(),
                        Collectors.mapping(h -> h.getUser().getUsername(), Collectors.toList())));
    }

    private LiveSessionResponse describeForOwner(LiveSession session) {
        List<String> roster = hostRepository.findRoster(session.getId()).stream()
                .filter(LiveHost::isOnAir)
                .map(h -> h.getUser().getUsername())
                .toList();
        return LiveSessionResponse.of(session, true, roster, priceOf(session));
    }

    private Long priceOf(LiveSession session) {
        return session.isFree() ? null : pricing.priceOf(session);
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
