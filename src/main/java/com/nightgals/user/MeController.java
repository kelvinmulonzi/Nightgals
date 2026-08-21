package com.nightgals.user;

import com.nightgals.common.ErrorResponse;
import com.nightgals.user.dto.MeResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.PostMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.nightgals.profile.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "0. Account", description = """
        Who am I, what kind of account is this, and what should the app show next.

        `nextStep` is the field to drive the UI from. A **viewer** is always `BROWSE` -
        there is nothing for them to complete. A **creator** walks `CREATE_PROFILE` ->
        `SUBMIT_KYC` -> `AWAIT_REVIEW` -> `DONE`.
        """)
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final ProfileRepository profileRepository;
    private final AccountUpgradeService accountUpgradeService;

    @Operation(
            summary = "The caller's account state",
            description = """
                    The single call a client makes on launch to decide what to show:
                    the onboarding wizard, a "verification pending" screen, or the full app.

                    `nextStep` tells you exactly where the user is in onboarding.
                    """)
    @ApiResponse(responseCode = "200", description = "Account state")
    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthUser principal) {
        return MeResponse.of(principal.user(), profileRepository.existsByUserId(principal.id()));
    }

    @Operation(
            summary = "Become a creator",
            description = """
                    Turns a viewer account into a creator account, so the holder can post
                    content and earn from it. Nothing is lost - unlocks, subscriptions and
                    payment history all carry over, and there is no second account.

                    **Optional.** Submitting a profile or starting identity verification
                    upgrades the account anyway. Use this when the client wants an explicit
                    "I want to start creating" step before showing the onboarding form.

                    `nextStep` moves from `BROWSE` to `CREATE_PROFILE`, and from there the
                    usual path: profile, identity documents, review.

                    Already a creator? This is a no-op.
                    """)
    @ApiResponse(responseCode = "200", description = "Now a creator account")
    @ApiResponse(responseCode = "403", description = "Account is suspended",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/become-creator")
    public MeResponse becomeCreator(@AuthenticationPrincipal AuthUser principal) {
        return accountUpgradeService.becomeCreator(principal.user());
    }

    @Operation(
            summary = "Go back to a viewer account",
            description = """
                    The reverse of `become-creator`, for somebody who signed up to post and
                    decided they only want to watch. Same account, same handle, same email,
                    same purchases - only the type changes, and `nextStep` becomes `BROWSE`
                    so the onboarding funnel stops asking for a profile and documents.

                    **Refused while the account still owns something only a creator can.**
                    The code says which:

                    * `has_media` - items are still posted. Delete them first.
                    * `has_earnings` - money is owed that has not been paid out. Withdraw it.
                    * `kyc_in_review` - an identity check is with a moderator. Wait for it.

                    A completed identity check is *not* a blocker, and the verdict is kept:
                    it is a fact about a document, not a permission, and throwing it away
                    would mean asking for a passport twice if they ever came back.

                    Already a viewer? This is a no-op.
                    """)
    @ApiResponse(responseCode = "200", description = "Now a viewer account")
    @ApiResponse(responseCode = "403", description = "Account is suspended",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Media, earnings or a pending identity check remain",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/become-viewer")
    public MeResponse becomeViewer(@AuthenticationPrincipal AuthUser principal) {
        return accountUpgradeService.becomeViewer(principal.user());
    }
}
