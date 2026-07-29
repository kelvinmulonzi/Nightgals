package com.nightgals.discovery;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "7. Browse", description = """
        The scroll feed. **Public - no sign-in required.**

        This is the shop window. Anyone can see who is on Nightgals and decide whether
        it is worth joining. A card carries the handle, age, city, vibe, bio and the
        free preview photo, plus honest counts of what is behind the paywall
        (`lockedPhotoCount`, `lockedVideoCount`, `liveNow`).

        Signing in changes nothing here except that unlocked creators come back with
        `unlocked: true` and their full gallery.
        """)
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @Operation(
            summary = "Browse members",
            description = """
                    Verified, discoverable creators, newest first. Signed-in callers do not
                    see their own card.

                    Everything on the card is free and public. To see the rest, sign in and
                    unlock the creator with `POST /api/v1/billing/unlocks/{userId}`, or subscribe.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "A page of member cards")
    @GetMapping
    public PageResponse<MemberCardResponse> browse(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "Filter to one city, e.g. Nairobi") @RequestParam(required = false) String city,
            @PageableDefault(size = 20) Pageable pageable) {
        return feedService.feed(AuthUser.userOrNull(principal), city, pageable);
    }
}
