package com.nightgals.user;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.user.dto.AdminUserResponse;
import com.nightgals.user.dto.SuspendUserRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "15. Accounts (admin)", description = """
        Regulating members: who is on the site, and who is off it.

        `ADMIN` only. A moderator works the review queues — deciding one document or
        one photo — and that is a different job from removing a person. Burning an
        account is the widest single action in the system, so it sits with the role
        that already holds the settlement desk and the revenue figures.

        **Burning is reversible and destroys nothing.** It flips the account to
        `SUSPENDED`, which refuses sign-in, revokes every session it holds, and drops
        it and all of its work out of every public listing — Discover, the video wall,
        the reel feed, its profile and its gallery. Restoring puts all of it back
        exactly as it was.
        """)
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(
            summary = "List and search accounts",
            description = """
                    Newest first. `q` matches the address or the handle, case-insensitively
                    and anywhere in the string. `status` narrows to one state; omit it for
                    everybody.
                    """)
    @GetMapping
    public PageResponse<AdminUserResponse> list(
            @Parameter(description = "Part of an email address or handle") @RequestParam(required = false) String q,
            @Parameter(description = "ACTIVE, SUSPENDED or DEACTIVATED. Omit for all.")
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminUserService.list(q, status, pageable);
    }

    @Operation(summary = "How many accounts are in each state")
    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return adminUserService.counts();
    }

    @Operation(summary = "One account")
    @ApiResponse(responseCode = "404", description = "No such account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{userId}")
    public AdminUserResponse get(@PathVariable UUID userId) {
        return adminUserService.get(userId);
    }

    @Operation(
            summary = "Burn an account",
            description = """
                    Signs the member out everywhere, refuses any further sign-in, and takes
                    the account and everything it has published out of public view. Nothing
                    is deleted — `POST /restore` puts it all back.

                    A reason is required, and is recorded against the account for other
                    staff. It is never shown to the member.

                    You cannot burn yourself, and you cannot burn another administrator.
                    """)
    @ApiResponse(responseCode = "200", description = "Burned")
    @ApiResponse(responseCode = "409", description = "Yourself, another administrator, or already burned",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{userId}/suspend")
    public AdminUserResponse suspend(@PathVariable UUID userId,
                                     @Valid @RequestBody SuspendUserRequest request,
                                     @AuthenticationPrincipal AuthUser principal) {
        return adminUserService.suspend(userId, request, principal.user());
    }

    @Operation(
            summary = "Unburn an account",
            description = """
                    Puts the account back to `ACTIVE`: sign-in works again and everything it
                    published returns to public view.

                    The record of the burn — when, why, by whom — is kept rather than
                    cleared. A second incident on the same account is exactly when a
                    moderator wants to see the first one.
                    """)
    @ApiResponse(responseCode = "409", description = "That account is not burned",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{userId}/restore")
    public AdminUserResponse restore(@PathVariable UUID userId,
                                     @AuthenticationPrincipal AuthUser principal) {
        return adminUserService.restore(userId, principal.user());
    }
}
