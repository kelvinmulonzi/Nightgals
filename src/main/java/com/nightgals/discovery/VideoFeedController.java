package com.nightgals.discovery;

import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.VideoCardResponse;
import com.nightgals.media.ContentTier;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
        The video wall. **Public - no sign-in required.**

        Every clip on the platform in one place, newest first, from the same population
        `GET /members` draws from. Filter with `tier`: FREE is the shop window, EXCLUSIVE
        is what viewers pay for.
        """)
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoFeedController {

    private final FeedService feedService;

    @Operation(
            summary = "Browse videos from everyone",
            description = """
                    Newest first, across every verified, discoverable creator.

                    **`tier` filters what is listed, not what you can watch.** Ask for
                    `EXCLUSIVE` and you get the paywalled clips whether or not you have paid
                    for them - the ones you have not come back with `locked: true`, a price and
                    no `url`, which is what lets a client show a blurred tile worth buying.
                    Omit it for both.

                    Ordered by recency alone. Package rank lifts creators up `GET /members`,
                    which is a listing of people; stacking one catalogue above everybody
                    else's newest work would make a worse wall.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "A page of clips, locked ones included")
    @GetMapping
    public PageResponse<VideoCardResponse> browse(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "FREE or EXCLUSIVE. Omit for both.")
            @RequestParam(required = false) ContentTier tier,
            @PageableDefault(size = 24) Pageable pageable) {
        return feedService.videos(AuthUser.userOrNull(principal), tier, pageable);
    }
}
