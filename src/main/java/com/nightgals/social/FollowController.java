package com.nightgals.social;

import com.nightgals.common.PageResponse;
import com.nightgals.social.dto.FollowedCreatorResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "12. Following", description = """
        Following a creator.

        Free, one-directional, and needing no approval - it is a subscription to
        somebody's schedule, not a relationship, and it grants access to nothing.

        Its purpose is reminders: a follower with `remind` on is emailed shortly before
        each of her scheduled broadcasts.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @Operation(summary = "Follow a creator")
    @ApiResponse(responseCode = "204", description = "Following")
    @PostMapping("/members/{userId}/follow")
    public ResponseEntity<Void> follow(
            @AuthenticationPrincipal AuthUser principal,
            @PathVariable UUID userId,
            @Parameter(description = "Email me before her broadcasts. Defaults to true.")
            @RequestParam(defaultValue = "true") boolean remind) {
        followService.follow(principal.user(), userId, remind);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unfollow a creator")
    @ApiResponse(responseCode = "204", description = "No longer following")
    @DeleteMapping("/members/{userId}/follow")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal AuthUser principal,
                                         @PathVariable UUID userId) {
        followService.unfollow(principal.user(), userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Who I follow")
    @ApiResponse(responseCode = "200", description = "Creators followed, newest first")
    @GetMapping("/me/following")
    public PageResponse<FollowedCreatorResponse> following(
            @AuthenticationPrincipal AuthUser principal,
            @PageableDefault(size = 30) Pageable pageable) {
        return followService.following(principal.user(), pageable);
    }
}
