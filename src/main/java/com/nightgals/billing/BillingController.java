package com.nightgals.billing;

import com.nightgals.billing.dto.BuyPackageRequest;
import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.CreatorPackageResponse;
import com.nightgals.billing.dto.CreatorPackageStatusResponse;
import com.nightgals.billing.dto.EntitlementResponse;
import com.nightgals.billing.dto.PlanResponse;
import com.nightgals.billing.dto.PurchaseResponse;
import com.nightgals.billing.dto.SubscribeRequest;
import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
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

import java.util.UUID;

@Tag(name = "9. Billing", description = """
        Money, in both directions.

        **Viewers pay creators.** Browsing is free - the feed, every profile card, and
        whatever each creator has marked FREE. Everything she marked EXCLUSIVE costs
        one payment, at *her* price, and it opens all of it. There is no photo tier and
        no video tier: you pick a person, not a menu.

        **Creators pay the platform.** Publishing needs a package -
        `BRONZE` (photos), `SILVER` (video) or `GOLD` (both) - each with its own
        allowance. See `GET /billing/creator-packages`.

        **No payment provider is integrated yet.** Purchases are created `PENDING` and
        the configured provider says how to pay. The default returns `action: MANUAL`
        with instructions, and an administrator settles the purchase once money
        arrives. When a real API (M-Pesa Daraja, a card gateway) is wired in, only that
        provider changes - the access rules, entitlements and endpoints below stay
        exactly as they are.
        """)
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final CreatorPackageService creatorPackageService;

    @Operation(summary = "Prices and plans",
            description = "What things cost and how much is free. Open, so a paywall screen can render before sign-in.",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The catalogue")
    @GetMapping("/plans")
    public PlanResponse plans() {
        return billingService.plans();
    }

    @Operation(
            summary = "Unlock one member",
            description = """
                    Starts a payment that grants access to this member's photos, video and
                    live sessions for the configured period.

                    Returns the purchase in `PENDING` plus how to pay. Access is granted only
                    once the purchase reaches `COMPLETED` - poll
                    `GET /api/v1/billing/purchases` or `GET /api/v1/billing/entitlements`.

                    Calling it twice for the same member returns the existing pending purchase
                    rather than creating a second one.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "404", description = "No such member, or they are not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already unlocked, or monetisation is switched off",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/unlocks/{userId}")
    public CheckoutResponse unlockProfile(@AuthenticationPrincipal AuthUser principal,
                                          @PathVariable UUID userId) {
        return billingService.unlockProfile(principal.user(), userId);
    }

    @Operation(summary = "Subscribe",
            description = """
                    Starts a payment for a plan from `GET /api/v1/billing/plans`. An active
                    subscription unlocks every member, so no individual unlocks are needed.

                    Renewing while still active extends from the current expiry - nobody loses
                    days by paying early.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "Unknown plan code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/subscriptions")
    public CheckoutResponse subscribe(@AuthenticationPrincipal AuthUser principal,
                                      @Valid @RequestBody SubscribeRequest request) {
        return billingService.subscribe(principal.user(), request.planCode());
    }

    // ------------------------------------------------------------ creator side

    @Operation(summary = "The three creator packages",
            description = """
                    What a creator pays the platform for the right to publish, cheapest first.

                    * **Bronze** - photos only, small allowance
                    * **Silver** - video only, small allowance
                    * **Gold** - photos and video, large allowance

                    `maxPhotos: 0` means that package does not cover photos at all, and the
                    same for `maxVideos`. Open, so the pricing page renders before sign-in.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The packages on sale")
    @GetMapping("/creator-packages")
    public java.util.List<CreatorPackageResponse> creatorPackages() {
        return creatorPackageService.catalogue();
    }

    @Operation(summary = "My package, and how much of it is left",
            description = """
                    The one call the studio makes before showing an upload button.

                    `canPostPhotos` and `canPostVideos` already account for the package
                    covering that media type *and* having room left, so a client does not have
                    to work out the combination itself. `available` carries the full catalogue
                    for rendering an upgrade path.
                    """)
    @ApiResponse(responseCode = "200", description = "Publishing rights and remaining allowance")
    @GetMapping("/creator-packages/mine")
    public CreatorPackageStatusResponse myPackage(@AuthenticationPrincipal AuthUser principal) {
        return creatorPackageService.status(principal.user());
    }

    @Operation(summary = "Buy a creator package",
            description = """
                    Starts a payment for `BRONZE`, `SILVER` or `GOLD`. Publishing unlocks once
                    the purchase reaches `COMPLETED`.

                    Buying the same package again while it is still running extends it from the
                    current expiry, so renewing early costs no days. Buying a *different* one
                    starts a fresh period at the new allowance.

                    Identity verification is not required to buy - paying while documents are
                    in review means the package is live the moment approval lands.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "Unknown package code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Creator packages are switched off here",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/creator-packages")
    public CheckoutResponse buyCreatorPackage(@AuthenticationPrincipal AuthUser principal,
                                              @Valid @RequestBody BuyPackageRequest request) {
        return billingService.buyCreatorPackage(principal.user(), request.packageCode());
    }

    // ------------------------------------------------------------ reads

    @Operation(summary = "What I currently have access to",
            description = "The one call a client makes to decide whether to draw paywalls.")
    @ApiResponse(responseCode = "200", description = "Active subscription and unlocked members")
    @GetMapping("/entitlements")
    public EntitlementResponse entitlements(@AuthenticationPrincipal AuthUser principal) {
        return billingService.entitlements(principal.user());
    }

    @Operation(summary = "My payment history")
    @ApiResponse(responseCode = "200", description = "Purchases, newest first")
    @GetMapping("/purchases")
    public PageResponse<PurchaseResponse> purchases(@AuthenticationPrincipal AuthUser principal,
                                                    @PageableDefault(size = 20) Pageable pageable) {
        return billingService.history(principal.id(), pageable);
    }
}
