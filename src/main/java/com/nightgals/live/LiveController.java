package com.nightgals.live;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.live.dto.ExtendLiveRequest;
import com.nightgals.live.dto.GiftFeedResponse;
import com.nightgals.live.dto.GiftHistoryResponse;
import com.nightgals.live.dto.GiftTotalsResponse;
import com.nightgals.live.dto.GiftOptionResponse;
import com.nightgals.live.dto.GiftResponse;
import com.nightgals.live.dto.LiveAllowanceResponse;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.live.dto.LiveSessionResponse;
import com.nightgals.live.dto.SendGiftRequest;
import com.nightgals.live.dto.StreamCredentialsResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "8. Live sessions", description = """
        Live broadcasts.

        Nightgals stores session metadata only - it does not ingest or serve video.
        The host supplies a `playbackUrl` from whatever streaming provider they use,
        and this API decides who is allowed to see that URL.

        Broadcasting requires `APPROVED`.

        **Every session is paid to join.** It is created `EXCLUSIVE` and carries a price
        the host sets herself; `accessPriceMinor` is required, there is no free tier, and
        no default is invented for a creator who did not name one.

        Entry is bought per broadcast, so unlocking a creator's gallery is not a ticket to
        her room and one show is not a ticket to the next. Gifts sit on top of the door
        charge rather than replacing it.

        Every session therefore appears in the public listing with `locked: true` and no
        `playbackUrl`, so people can see it is happening and what it costs to come in.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LiveController {

    private final LiveSessionService liveSessionService;
    private final LiveQuotaService liveQuotaService;
    private final BillingService billingService;
    private final GiftService giftService;
    private final MonetizationProperties monetization;

    @Operation(summary = "Announce or start a broadcast",
            description = "Omit `scheduledFor` to go live immediately. **Requires: APPROVED.**")
    @ApiResponse(responseCode = "200", description = "Session created")
    @ApiResponse(responseCode = "403", description = "Identity not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already broadcasting",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/me/live")
    public LiveSessionResponse create(@AuthenticationPrincipal AuthUser principal,
                                      @Valid @RequestBody LiveSessionRequest request) {
        return liveSessionService.create(principal.user(), request);
    }

    @Operation(summary = "My sessions", description = "Playback URLs are always visible to their host.")
    @ApiResponse(responseCode = "200", description = "The caller's sessions")
    @GetMapping("/me/live")
    public List<LiveSessionResponse> mine(@AuthenticationPrincipal AuthUser principal) {
        return liveSessionService.mine(principal.id());
    }

    @Operation(summary = "How many live minutes I have left today",
            description = """
                    The daily allowance, what has been used, and what any top-up added.

                    `remainingMinutes` counts a broadcast that is on air right now, so it
                    falls while streaming - which is what makes it usable for a "you have
                    5 minutes left" warning rather than a figure that only moves once the
                    stream ends.
                    """)
    @ApiResponse(responseCode = "200", description = "Today's allowance")
    @GetMapping("/me/live/allowance")
    public LiveAllowanceResponse allowance(@AuthenticationPrincipal AuthUser principal) {
        return LiveAllowanceResponse.of(
                liveQuotaService.remainingToday(principal.user()),
                monetization.liveExtension(),
                monetization.currency());
    }

    @Operation(summary = "Buy extra live minutes for today",
            description = """
                    Tops up today's allowance so a broadcast that is running long does not
                    get cut off. The minutes are for today only and expire with it.

                    Like every other purchase this returns a CheckoutResponse: on mobile
                    money it comes back `PROMPT_ON_PHONE` and `PENDING`, and **the minutes
                    are not granted until it settles**. Poll the purchase rather than
                    assuming the extension is live.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "402", description = "No package, so there is no allowance to extend",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Outside the daily bounds",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/me/live/extend")
    public CheckoutResponse extend(@AuthenticationPrincipal AuthUser principal,
                                   @Valid @RequestBody ExtendLiveRequest request) {
        return billingService.buyLiveExtension(
                principal.user(),
                request.minutes(),
                com.nightgals.billing.PaymentChoice.of(request.method(), request.payerMsisdn()));
    }

    @Operation(summary = "Go live with a scheduled session")
    @ApiResponse(responseCode = "200", description = "Now broadcasting")
    @PostMapping("/me/live/{sessionId}/start")
    public LiveSessionResponse start(@AuthenticationPrincipal AuthUser principal,
                                     @PathVariable UUID sessionId) {
        return liveSessionService.start(principal.user(), sessionId);
    }

    @Operation(summary = "End a broadcast")
    @ApiResponse(responseCode = "200", description = "Session ended")
    @PostMapping("/me/live/{sessionId}/end")
    public LiveSessionResponse end(@AuthenticationPrincipal AuthUser principal,
                                   @PathVariable UUID sessionId) {
        return liveSessionService.end(principal.user(), sessionId);
    }

    @Operation(summary = "Edit a scheduled broadcast",
            description = """
                    Moving the start time clears the reminder flag, so followers are told
                    again about the new time rather than the old one.
                    """)
    @ApiResponse(responseCode = "200", description = "Updated")
    @PutMapping("/me/live/{sessionId}")
    public LiveSessionResponse update(@AuthenticationPrincipal AuthUser principal,
                                      @PathVariable UUID sessionId,
                                      @Valid @RequestBody LiveSessionRequest request) {
        return liveSessionService.update(principal.user(), sessionId, request);
    }

    // ------------------------------------------------------------ co-hosting

    @Operation(summary = "Invite somebody to co-host",
            description = """
                    A session keeps one owner - the minutes come off her daily allowance and
                    the money is hers. A co-host appears in it and spends none of their own.

                    Re-inviting somebody who declined reopens the invitation rather than failing.
                    """)
    @ApiResponse(responseCode = "204", description = "Invited")
    @PostMapping("/me/live/{sessionId}/hosts/{userId}")
    public ResponseEntity<Void> invite(@AuthenticationPrincipal AuthUser principal,
                                       @PathVariable UUID sessionId,
                                       @PathVariable UUID userId) {
        liveSessionService.invite(principal.user(), sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Take somebody off a session I own")
    @ApiResponse(responseCode = "204", description = "Removed")
    @DeleteMapping("/me/live/{sessionId}/hosts/{userId}")
    public ResponseEntity<Void> removeHost(@AuthenticationPrincipal AuthUser principal,
                                           @PathVariable UUID sessionId,
                                           @PathVariable UUID userId) {
        liveSessionService.removeHost(principal.user(), sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sessions I have been invited to co-host")
    @ApiResponse(responseCode = "200", description = "Outstanding invitations")
    @GetMapping("/me/live/invitations")
    public List<LiveSessionResponse> invitations(@AuthenticationPrincipal AuthUser principal) {
        return liveSessionService.pendingInvites(principal.user());
    }

    @Operation(summary = "Accept or decline a co-host invitation")
    @ApiResponse(responseCode = "204", description = "Answered")
    @PostMapping("/me/live/invitations/{sessionId}")
    public ResponseEntity<Void> respond(@AuthenticationPrincipal AuthUser principal,
                                        @PathVariable UUID sessionId,
                                        @RequestParam boolean accept) {
        liveSessionService.respondToInvite(principal.user(), sessionId, accept);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ the calendar

    @Operation(summary = "What is coming up",
            description = """
                    The calendar: scheduled broadcasts still to come, soonest first. Public,
                    so somebody can see what is on before deciding to follow anybody.

                    Followers of a creator are emailed shortly before each of her sessions.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Upcoming broadcasts")
    @GetMapping("/live/upcoming")
    public PageResponse<LiveSessionResponse> upcoming(@AuthenticationPrincipal AuthUser principal,
                                                      @PageableDefault(size = 30) Pageable pageable) {
        return liveSessionService.upcoming(AuthUser.userOrNull(principal), pageable);
    }

    @Operation(summary = "Who is broadcasting right now",
            description = """
                    **Public - no sign-in required.** The listing is the advert.

                    Sessions the caller has not unlocked are listed with `locked: true` and no
                    playback URL. Getting the URL needs a signed-in, paying viewer.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Live sessions")
    @GetMapping("/live")
    public PageResponse<LiveSessionResponse> live(@AuthenticationPrincipal AuthUser principal,
                                                  @PageableDefault(size = 20) Pageable pageable) {
        return liveSessionService.live(AuthUser.userOrNull(principal), pageable);
    }

    @Operation(summary = "A member's sessions")
    @ApiResponse(responseCode = "200", description = "That member's sessions")
    @GetMapping("/members/{userId}/live")
    public List<LiveSessionResponse> forMember(@PathVariable UUID userId,
                                               @AuthenticationPrincipal AuthUser principal) {
        return liveSessionService.forHost(userId, principal.user());
    }

    @Operation(summary = "Get the playback URL for a session",
            description = """
                    Every broadcast sells entry, and it says so differently depending on who
                    is asking: **401** to a stranger, because getting an account is the first
                    step and an auth error is not a thing to show somebody who only wanted to
                    watch, and **402** to a member who simply has not bought this one, so the
                    client can open the paywall.

                    The endpoint is unauthenticated so that the 401 comes from here, with a
                    sentence a viewer can act on, rather than from the security filter.
                    """)
    @ApiResponse(responseCode = "200", description = "The playback URL")
    @ApiResponse(responseCode = "402", description = "Host not unlocked",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/live/{sessionId}/playback")
    public Map<String, String> playback(@PathVariable UUID sessionId,
                                        @AuthenticationPrincipal AuthUser principal) {
        // userOrNull, not user(): there may be no principal at all here now, and
        // the entitlement check is what decides whether that is allowed.
        return Map.of("playbackUrl",
                liveSessionService.playbackUrl(sessionId, AuthUser.userOrNull(principal)));
    }

    @Operation(summary = "How to watch a session",
            description = """
                    What replaces `/playback`. That endpoint answers with a URL, which only
                    a provider serving HLS actually has - on a room-based provider there is
                    no URL to give and it fails. This one asks the configured provider what
                    a viewer's client needs, so read `mode` and act on it:

                    - `WEBRTC` - connect to `url` with `token` and join `room`
                    - `HLS` - play `url`

                    **Credentials are minted per request, after the access check, and
                    expire.** That is the point of the change: a stored URL, once handed
                    out, is a password nobody can take back.

                    Access is decided exactly as it is for `/playback`: 401 to a stranger,
                    402 to a member who has not paid for this broadcast.
                    """)
    @ApiResponse(responseCode = "200", description = "How to connect, for this viewer, for now")
    @ApiResponse(responseCode = "402", description = "Host not unlocked",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/live/{sessionId}/watch")
    public StreamCredentialsResponse watch(@PathVariable UUID sessionId,
                                           @AuthenticationPrincipal AuthUser principal) {
        // userOrNull, not user(): an anonymous caller must reach the service and
        // be turned away by the entitlement check, which answers 401 with a
        // sentence about creating an account. Demanding a principal here would
        // hand them the security filter's words instead.
        return StreamCredentialsResponse.of(
                liveSessionService.watch(sessionId, AuthUser.userOrNull(principal)));
    }

    @Operation(summary = "How to broadcast a session",
            description = """
                    The host's side of `/watch`, and the reason a creator no longer copies a
                    stream key anywhere: on a provider that provisions its own ingest this
                    returns a publisher token, and the browser publishes camera and
                    microphone straight into the room.

                    Separate from creating the session, and re-issued on demand, because
                    these expire - a creator who scheduled a broadcast for tomorrow asks
                    again when she actually goes on air.

                    Restricted to the session's host and to co-hosts who have accepted an
                    invitation and are on air. Publishing credentials **are** the broadcast:
                    anyone holding them can appear on it.
                    """)
    @ApiResponse(responseCode = "200", description = "How to publish, for this host, for now")
    @ApiResponse(responseCode = "403", description = "`not_your_session`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/live/{sessionId}/publish")
    public StreamCredentialsResponse publish(@AuthenticationPrincipal AuthUser principal,
                                             @PathVariable UUID sessionId) {
        return StreamCredentialsResponse.of(
                liveSessionService.publish(sessionId, principal.user()));
    }

    // ------------------------------------------------------------------ gifts

    @Operation(summary = "What can be sent to a creator on air",
            description = """
                    The gift picker. Public, so the options can be shown before sign-in -
                    it reads configuration and exposes nobody's data.

                    Prices are in the currency's minor unit. `icon` is an emoji, so a
                    client can render the whole catalogue without fetching any images.
                    """)
    @ApiResponse(responseCode = "200", description = "Sendable gifts, cheapest first")
    @ApiResponse(responseCode = "409", description = "`gifts_disabled` - not available here",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/live/gifts")
    public List<GiftOptionResponse> giftCatalogue() {
        return giftService.catalogue();
    }

    @Operation(summary = "Send a gift to the creator on air",
            description = """
                    Spends the sender's balance - no payment happens here, which is what
                    makes it instant. The money was taken when the balance was topped up
                    at `POST /api/v1/billing/credit/top-up`.

                    A `409 insufficient_credit` is the cue to open the top-up screen; the
                    message carries the current balance. The debit, the gift and the
                    creator's earning are one transaction, so a failure leaves nothing
                    half-done.
                    """,
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Sent, and now visible on the broadcast")
    @ApiResponse(responseCode = "400",
            description = "`unknown_gift` or `self_gift` - a creator cannot gift her own broadcast",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such broadcast",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "`insufficient_credit`, `not_live`, or `gifts_disabled`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/live/{sessionId}/gifts")
    public GiftResponse sendGift(@PathVariable UUID sessionId,
                                 @AuthenticationPrincipal AuthUser principal,
                                 @Valid @RequestBody SendGiftRequest request) {
        return giftService.send(principal.user(), sessionId, request.giftCode(), request.message());
    }

    @Operation(summary = "Gifts sent to a broadcast",
            description = """
                    Polled by the viewer's client to animate gifts as they arrive.

                    Omit `since` on the first call and the last 50 gifts come back, so a
                    viewer joining midway does not face a blank overlay. Then send back the
                    `until` value from the previous response - it is the server's own
                    clock, which is what stops gifts being replayed or skipped when the
                    two machines disagree about the time.
                    """)
    @ApiResponse(responseCode = "200", description = "Gifts since `since`, oldest first")
    @ApiResponse(responseCode = "404", description = "No such broadcast",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/live/{sessionId}/gifts")
    public GiftFeedResponse gifts(
            @PathVariable UUID sessionId,
            @Parameter(description = "The `until` from the last response. Omit on first call.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return giftService.feed(sessionId, since);
    }

    // -------------------------------------------------- gifts, after the fact

    @Operation(
            summary = "What I have gifted and been gifted",
            description = """
                    Totals for the signed-in account, in both directions, all time.

                    `sentMinor` is money spent and gone. `receivedMinor` is **gross** - what
                    viewers paid before the platform's commission - so it is a headline
                    figure, not a balance. What a creator can actually withdraw is on
                    `GET /me/earnings`, and the two should never be shown as the same thing.
                    """)
    @ApiResponse(responseCode = "200", description = "Gift totals for this account")
    @GetMapping("/me/gifts")
    public GiftTotalsResponse giftTotals(@AuthenticationPrincipal AuthUser principal) {
        return giftService.totalsFor(principal.id());
    }

    @Operation(
            summary = "Every gift I have sent",
            description = """
                    Newest first, each naming the creator and the broadcast it was sent in.

                    The room's feed is ephemeral - it empties when the broadcast ends - so
                    this is where somebody comes to see where their balance actually went.
                    """)
    @ApiResponse(responseCode = "200", description = "Gifts sent, newest first")
    @GetMapping("/me/gifts/sent")
    public PageResponse<GiftHistoryResponse> giftsSent(@AuthenticationPrincipal AuthUser principal,
                                                       @PageableDefault(size = 20) Pageable pageable) {
        return giftService.sentBy(principal.id(), pageable);
    }

    @Operation(
            summary = "Every gift I have received",
            description = """
                    For a creator: who sent what, in which broadcast, and what they said with
                    it. Amounts are gross - her share of each is on her earnings ledger.
                    """)
    @ApiResponse(responseCode = "200", description = "Gifts received, newest first")
    @GetMapping("/me/gifts/received")
    public PageResponse<GiftHistoryResponse> giftsReceived(@AuthenticationPrincipal AuthUser principal,
                                                           @PageableDefault(size = 20) Pageable pageable) {
        return giftService.receivedBy(principal.id(), pageable);
    }
}
