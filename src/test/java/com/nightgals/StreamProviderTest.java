package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.PaymentChoice;
import com.nightgals.live.LiveSessionService;
import com.nightgals.live.StreamProvider;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provisioning a place to broadcast, and who is allowed into it.
 *
 * <p>Runs against LiveKit's provider with a fake key and secret. Nothing here
 * reaches the network and nothing needs to: rooms are created by the first
 * participant to arrive, so the platform's entire job is minting tokens, and a
 * token can be verified locally by the same secret that signed it.
 *
 * <p>What is actually being tested is the access boundary. Once a client holds a
 * token it talks to LiveKit directly and this server never sees it again, so the
 * permissions baked into it are the last word - in particular that a viewer's
 * token cannot publish.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.live.provider=livekit",
        "nightgals.livekit.url=wss://test.livekit.cloud",
        "nightgals.livekit.api-key=APItest123",
        // 32+ bytes, because HS256 needs 256 bits and the provider refuses less.
        "nightgals.livekit.api-secret=test-secret-that-is-long-enough-for-hs256",
        "nightgals.livekit.token-ttl=PT1H",
        "nightgals.monetization.free-trial=PT0S",
        "nightgals.creator-packages.enabled=false",
})
@Transactional
class StreamProviderTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256";

    @Autowired AuthService authService;
    @Autowired BillingService billingService;
    @Autowired ProfileService profileService;
    @Autowired LiveSessionService liveSessionService;
    @Autowired StreamProvider streamProvider;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("LiveKit is the wired-in provider when configured")
    void livekitIsSelected() {
        assertThat(streamProvider.name()).isEqualTo("LIVEKIT");
    }

    @Test
    @DisplayName("Two creators broadcast at once, each into her own room")
    void manyCreatorsBroadcastConcurrently() {
        // Two creators rather than two sessions from one: a creator may only have
        // one broadcast on air at a time, which the service enforces.
        var first = liveSessionService.create(approvedCreator(), request("One"));
        var second = liveSessionService.create(approvedCreator(), request("Two"));

        // The heart of it. This is what a single-instance server like Owncast
        // cannot do - one stream key, so the second broadcast lands on top of the
        // first. A room per session is what makes the platform multi-creator.
        assertThat(first.playbackUrl()).isNotEqualTo(second.playbackUrl());
        assertThat(first.playbackUrl()).isEqualTo("live-" + first.id());
        assertThat(second.playbackUrl()).isEqualTo("live-" + second.id());
    }

    @Test
    @DisplayName("A client-supplied playbackUrl is discarded, not honoured")
    void suppliedUrlIsIgnored() {
        User creator = approvedCreator();

        var session = liveSessionService.create(creator, new LiveSessionRequest(
                "Mine", "https://evil.example.com/somebody-elses.m3u8",
                null, null, null, 3_000L));

        // Otherwise a session could be pointed at another broadcast's stream.
        assertThat(session.playbackUrl()).isEqualTo("live-" + session.id());
    }

    @Test
    @DisplayName("The host gets a token that may publish")
    void hostMayPublish() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();

        var credentials = liveSessionService.publish(sessionId, reload(creator));

        assertThat(credentials.mode()).isEqualTo(StreamProvider.Mode.WEBRTC);
        assertThat(credentials.url()).isEqualTo("wss://test.livekit.cloud");
        assertThat(credentials.room()).isEqualTo("live-" + sessionId);

        Map<?, ?> video = videoGrant(credentials.token());
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("room")).isEqualTo("live-" + sessionId);
        assertThat(video.get("roomJoin")).isEqualTo(true);
    }

    @Test
    @DisplayName("A viewer's token may watch but NOT publish")
    void viewerMayNotPublish() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();
        User viewer = paidViewer(sessionId);

        var credentials = liveSessionService.watch(sessionId, viewer);

        Map<?, ?> video = videoGrant(credentials.token());
        // Without this, a token good enough to watch is good enough to broadcast
        // into somebody else's room.
        assertThat(video.get("canPublish")).isEqualTo(false);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    @DisplayName("Tokens are signed so LiveKit will accept them, and identify the account")
    void tokensAreVerifiableAndIdentifyTheParticipant() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();
        User viewer = paidViewer(sessionId);

        Claims claims = parse(liveSessionService.watch(sessionId, viewer).token());

        // LiveKit finds the project by the issuer and rejects anything it cannot
        // verify with that project's secret.
        assertThat(claims.getIssuer()).isEqualTo("APItest123");
        // The account is still named, so a disconnect or a ban can find them.
        assertThat(claims.getSubject()).startsWith(viewer.getId().toString());
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
        // The handle, never the email - other participants can see this.
        assertThat(claims.get("name")).isEqualTo(viewer.getUsername());
        assertThat(String.valueOf(claims.get("name"))).doesNotContain("@");
    }

    @Test
    @DisplayName("A viewer who has not paid gets no token at all")
    void unpaidViewerIsRefused() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();

        // The door is here, not in the player. Once a token has been minted the
        // client talks to LiveKit directly and nothing on this server can take it
        // back, so refusing to mint one is the only enforcement there is.
        assertThatThrownBy(() -> liveSessionService.watch(sessionId, viewer()))
                .hasMessageContaining("Buy access to this broadcast");
    }

    @Test
    @DisplayName("A visitor with no account gets no token either")
    void anonymousViewerIsRefused() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();

        // 401 rather than 402: the first thing a stranger needs is an account,
        // and the client sends them to sign up instead of to a checkout.
        assertThatThrownBy(() -> liveSessionService.watch(sessionId, null))
                .hasMessageContaining("Sign in to watch");
    }

    @Test
    @DisplayName("Somebody else cannot get publishing credentials for a broadcast")
    void publishIsOwnerOnly() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();
        User stranger = viewer();

        assertThatThrownBy(() -> liveSessionService.publish(sessionId, stranger))
                .hasMessageContaining("not broadcasting this session");
    }

    @Test
    @DisplayName("Each call mints a fresh token rather than reusing a stored one")
    void tokensAreMintedPerCall() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();
        User viewer = paidViewer(sessionId);

        String first = liveSessionService.watch(sessionId, viewer).token();
        String second = liveSessionService.watch(sessionId, reload(viewer)).token();

        // Both valid, both for the same viewer - the point is that they are issued
        // per request behind the entitlement check, not stored and handed out.
        assertThat(parse(first).getSubject()).startsWith(viewer.getId().toString());
        assertThat(parse(second).getSubject()).startsWith(viewer.getId().toString());
    }

    @Test
    @DisplayName("Two connections from one viewer do not kick each other")
    void viewerIdentitiesAreUniquePerConnection() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();
        User viewer = paidViewer(sessionId);

        String first = parse(liveSessionService.watch(sessionId, viewer).token()).getSubject();
        String second = parse(liveSessionService.watch(sessionId, reload(viewer)).token()).getSubject();

        // LiveKit disconnects whoever already holds an identity when a second
        // token claims it. Sharing one across a viewer's connections meant an
        // ordinary second tab - or a page that remounts, which React does on
        // every mount in development - killed the connection that was working,
        // and the only way back was reloading until the race went the other way.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("The host keeps one identity, so a second studio replaces the first")
    void hostIdentityIsStable() {
        User creator = approvedCreator();
        UUID sessionId = liveSessionService.create(creator, request("Set")).id();

        String first = parse(liveSessionService.publish(sessionId, reload(creator)).token()).getSubject();
        String second = parse(liveSessionService.publish(sessionId, reload(creator)).token()).getSubject();

        // The opposite choice to a viewer's, and deliberate: opening the studio
        // twice should replace the broadcast, not put two of her on air.
        assertThat(first).isEqualTo(creator.getId().toString());
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------- helpers

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Map<?, ?> videoGrant(String token) {
        return (Map<?, ?>) parse(token).get("video");
    }

    private LiveSessionRequest request(String title) {
        // A price on every broadcast: the API refuses one without it.
        return new LiveSessionRequest(title, null, null, null, null, 3_000L);
    }

    private User approvedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        userRepository.saveAndFlush(managed);
        return reload(managed);
    }

    private User viewer() {
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    /**
     * A viewer who has bought their way into this broadcast.
     *
     * <p>Every broadcast is ticketed, so this is what an ordinary member watching
     * one looks like - and it goes through the real purchase rather than writing
     * an access row, because settlement is what grants entry and a test that
     * skipped it would keep passing after that broke.
     */
    private User paidViewer(UUID sessionId) {
        User viewer = viewer();
        var checkout = billingService.buyLiveAccess(viewer, sessionId, PaymentChoice.none());
        billingService.settle(checkout.purchase().id(), "TEST-REF");
        return reload(viewer);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
