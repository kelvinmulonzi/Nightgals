package com.nightgals.live;

import com.nightgals.common.ApiException;
import com.nightgals.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The host brings their own stream, and this platform only remembers the URL.
 *
 * <p>What Nightgals did before any provider existed, kept as the default. It is
 * what runs against the local Owncast container, and what a creator who prefers
 * her own setup - OBS to her own server, or a service this code has never heard
 * of - still gets.
 *
 * <p>No token, because there is nothing to mint one against: the URL <em>is</em>
 * the credential. Anyone who has it can watch, so the entitlement check in
 * {@link LiveSessionService} is the only thing standing between a viewer and the
 * stream, and it cannot revoke access once the URL has been handed out. That is
 * the honest limitation of this mode and the reason a token-based provider is
 * worth the work.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nightgals.live.provider", havingValue = "manual",
        matchIfMissing = true)
public class ManualStreamProvider implements StreamProvider {

    @Override
    public String name() {
        return "MANUAL";
    }

    @Override
    public Provisioned provision(LiveSession session, String requestedPlaybackUrl) {
        return new Provisioned(requestedPlaybackUrl);
    }

    /**
     * Nothing to hand over: the host already has whatever they are broadcasting
     * with, which is the point of this mode.
     */
    @Override
    public Credentials publishCredentials(LiveSession session, User host) {
        if (session.getPlaybackUrl() == null || session.getPlaybackUrl().isBlank()) {
            throw ApiException.badRequest("playback_url_required",
                    "Set a playbackUrl on the session - this deployment does not "
                    + "provision streams, so there is nowhere for viewers to watch.");
        }
        return Credentials.hls(session.getPlaybackUrl());
    }

    @Override
    public Credentials viewCredentials(LiveSession session, User viewer) {
        return Credentials.hls(session.getPlaybackUrl());
    }
}
