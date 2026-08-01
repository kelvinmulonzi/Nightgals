package com.nightgals.billing;

import com.nightgals.billing.dto.BuyPackageRequest;
import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.CreatorPackageResponse;
import com.nightgals.billing.dto.CreatorPackageStatusResponse;
import com.nightgals.billing.dto.EntitlementResponse;
import com.nightgals.billing.dto.PurchaseResponse;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "9. Billing", description = """
        Money, in both directions.

        **Viewers pay creators, per item.** One video, one broadcast, or one private
        call - each at the price its creator set on it. There is no bundle and no
        all-access pass: a fan pays for the thing they want to watch.

        **Creators pay the platform, weekly.** `PRO`, `DIAMOND` or `BLACK_DIAMOND`.
        Every package covers photos and video; they differ on how many premium videos
        may be posted, how many minutes of live per day, and where she ranks in search.

        **Everything is free for the first 7 days.** A new account is on trial and
        every paywall passes for it - viewers watch, creators publish - so nobody hits
        a payment screen before they have seen what they are buying.

        **Credit is spendable here.** Referral credit is applied before the payment
        provider is involved, so a purchase covered entirely by credit settles without
        a payment at all.

        **No payment provider is integrated yet.** Purchases are created `PENDING` and
        the configured provider says how to pay. The default settles instantly and
        collects nothing.
        """)
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final CreatorPackageService creatorPackageService;

    // ------------------------------------------------------------ viewers pay

    @Operation(
            summary = "Buy one photo or video",
            description = """
                    Charges the price the creator set on that item, and opens that item
                    only. Nothing else of hers comes with it.

                    Returns the purchase plus how to pay. Access is granted when the
                    purchase reaches `COMPLETED`, which with the default provider is
                    before this response is written - check `purchase.status` rather than
                    assuming it is pending.

                    Calling it twice returns the existing pending purchase rather than
                    opening a second one, and the price is fixed when that purchase is
                    created: a creator changing hers mid-checkout does not change what
                    you are charged.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "It is free, or it is your own",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such item",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already owned, or monetisation is off",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/media/{mediaId}")
    public CheckoutResponse unlockMedia(@AuthenticationPrincipal AuthUser principal,
                                        @PathVariable UUID mediaId) {
        return billingService.unlockMedia(principal.user(), mediaId);
    }

    @Operation(
            summary = "Buy entry to one live broadcast",
            description = """
                    Each stream carries its own access price, set by its host. Buying one
                    grants the playback URL for that stream and nothing else.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "It is open to everyone, or it is your own",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/live/{sessionId}")
    public CheckoutResponse buyLiveAccess(@AuthenticationPrincipal AuthUser principal,
                                          @PathVariable UUID sessionId) {
        return billingService.buyLiveAccess(principal.user(), sessionId);
    }

    // ------------------------------------------------------------ creators pay

    @Operation(summary = "The three creator packages",
            description = """
                    What a creator pays the platform, weekly, cheapest first.

                    | | Live per day | Premium videos | Placement |
                    |---|---|---|---|
                    | **Pro** | 15 minutes | 2 | standard |
                    | **Diamond** | 45 minutes | 5 | second |
                    | **Black Diamond** | 2 hours | 10 | highest |

                    Open, so the pricing page renders before sign-in.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The packages on sale")
    @GetMapping("/creator-packages")
    public List<CreatorPackageResponse> creatorPackages() {
        return creatorPackageService.catalogue();
    }

    @Operation(summary = "My package, and how much of it is left",
            description = """
                    The one call the studio makes before showing an upload button.

                    `canPostPhotos`, `canPostVideos` and `canGoLive` already fold together
                    "does the package cover this" and "is there room left", so a client
                    does not have to work out the combination itself.

                    `onTrial` is true for the first 7 days, when everything is unmetered
                    and `available` is what she will need to buy when it ends.
                    """)
    @ApiResponse(responseCode = "200", description = "Publishing rights and remaining allowance")
    @GetMapping("/creator-packages/mine")
    public CreatorPackageStatusResponse myPackage(@AuthenticationPrincipal AuthUser principal) {
        return creatorPackageService.status(principal.user());
    }

    @Operation(summary = "Buy a creator package",
            description = """
                    Starts a payment for `PRO`, `DIAMOND` or `BLACK_DIAMOND`.

                    Buying the same package again while it is still running extends it from
                    the current expiry, so renewing early costs no days. Buying a *different*
                    one starts a fresh period at the new allowance, and the longer-running
                    cover wins - an upgrade applies at once, a downgrade never claws back
                    time already paid for.

                    If somebody referred this account, their bonus is credited when this -
                    their **first** package - settles.
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

    @Operation(summary = "My standing: trial, credit, and what I own",
            description = """
                    Deliberately not a list of everything owned. Access is per item, and a
                    viewer with two hundred unlocked videos does not want them enumerated on
                    every page load - each gallery reports its own `locked` flags instead.
                    """)
    @ApiResponse(responseCode = "200", description = "Trial, credit balance and owned count")
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
