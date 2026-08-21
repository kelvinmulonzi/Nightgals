package com.nightgals.discovery;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.CityCountResponse;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.profile.Gender;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
// Required for the @Min/@Max on the query parameters below to be enforced at
// all - without it Spring binds them and the constraints are silently ignored.
@Validated
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
            @Parameter(description = """
                    Loose text match across handle, display name, city and bio. One box,
                    because somebody typing "Nairobi" into a search field means to find
                    Nairobi, not to be told to use the city filter instead.
                    """, example = "amina")
            @RequestParam(required = false) @Size(max = 60) String q,
            @Parameter(description = "Filter to one city, e.g. Nairobi") @RequestParam(required = false) String city,
            @Parameter(description = "FEMALE or MALE") @RequestParam(required = false) Gender gender,
            @Parameter(description = "Youngest age to include, inclusive", example = "21")
            @RequestParam(required = false) @Min(18) @Max(120) Integer minAge,
            @Parameter(description = "Oldest age to include, inclusive", example = "35")
            @RequestParam(required = false) @Min(18) @Max(120) Integer maxAge,
            @Parameter(description = "Only members broadcasting right now")
            @RequestParam(required = false) Boolean liveOnly,
            @Parameter(description = """
                    Only members holding a current creator package - Pro, Diamond or Black
                    Diamond. The same standing that already lifts them up the ordering.
                    """)
            @RequestParam(required = false) Boolean premiumOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return feedService.feed(AuthUser.userOrNull(principal), q, city,
                gender == null ? null : gender.name(),
                minAge, maxAge, liveOnly, premiumOnly, pageable);
    }

    @Operation(
            summary = "Cities with members in them",
            description = """
                    The city shortcuts shown beside the filters, commonest first.

                    Counted over exactly the population `GET /members` draws from - approved,
                    active and discoverable - so a shortcut always lands on the number it
                    advertised. Cities with no city set are left out rather than bucketed.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Cities, commonest first")
    @GetMapping("/cities")
    public List<CityCountResponse> cities(
            @Parameter(description = "How many to return, 1-50") 
            @RequestParam(defaultValue = "8") @Min(1) @Max(50) int limit) {
        return feedService.popularCities(limit);
    }
}
