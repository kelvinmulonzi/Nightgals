package com.nightgals.live;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.live.dto.LiveSessionResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "8. Live sessions", description = """
        Live broadcasts.

        Nightgals stores session metadata only - it does not ingest or serve video.
        The host supplies a `playbackUrl` from whatever streaming provider they use,
        and this API decides who is allowed to see that URL.

        Broadcasting requires `APPROVED`.

        Each session is either **FREE** - anyone can watch, signed in or not, which is
        useful for pulling people in - or **EXCLUSIVE**, which needs a viewer who has
        unlocked that creator or holds a subscription. Same `tier` as photos and video,
        set with the session.

        An exclusive session appears in the public listing with `locked: true` and no
        `playbackUrl`, so people can see it is happening and know what they are missing.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LiveController {

    private final LiveSessionService liveSessionService;

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
            description = "Returns 402 when the caller has not unlocked the host, so the client can open the paywall.")
    @ApiResponse(responseCode = "200", description = "The playback URL")
    @ApiResponse(responseCode = "402", description = "Host not unlocked",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/live/{sessionId}/playback")
    public Map<String, String> playback(@PathVariable UUID sessionId,
                                        @AuthenticationPrincipal AuthUser principal) {
        return Map.of("playbackUrl", liveSessionService.playbackUrl(sessionId, principal.user()));
    }
}
