package com.nightgals.billing;

import com.nightgals.billing.dto.BuyPackageRequest;
import com.nightgals.billing.dto.CheckoutRequest;
import com.nightgals.billing.dto.CheckoutResponse;
import com.nightgals.billing.dto.CreatorPackageResponse;
import com.nightgals.billing.dto.CreditBalanceResponse;
import com.nightgals.billing.dto.CreatorPackageStatusResponse;
import com.nightgals.billing.dto.EntitlementResponse;
import com.nightgals.billing.dto.PaymentMethodResponse;
import com.nightgals.billing.dto.PurchaseResponse;
import com.nightgals.billing.dto.TopUpRequest;
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

        **Pick how to pay, per purchase.** `GET /payment-methods` lists what this
        deployment accepts - MTN Mobile Money and card, typically - and every buy
        endpoint takes that `code` as `method`. Omitting it uses the platform
        default, so clients written before the picker keep working.

        **Nothing settles in the response, except by credit.** A purchase is created
        `PENDING` and the response says how to finish paying: `PROMPT_ON_PHONE` for
        Mobile Money, `REDIRECT` to a Stripe-hosted page for card. Money arrives out
        of band, so poll `GET /purchases` until the purchase reads `COMPLETED` or
        `FAILED` rather than assuming the checkout call decided anything.
        """)
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final CreatorPackageService creatorPackageService;

    // ------------------------------------------------------------ how to pay

    @Operation(summary = "The payment methods on offer",
            description = """
                    What the checkout picker should show, in the order to show it.

                    Send the `code` back as `method` on any of the buy endpoints below.
                    `requiresPayerMsisdn` says whether picking that one also means
                    collecting a phone number - read it rather than hard-coding which
                    methods want one, because that moves when a provider is swapped.

                    Exactly one entry has `isDefault: true`; that is what a checkout
                    naming no `method` gets.

                    Open, so the picker renders before sign-in.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Enabled payment methods")
    @GetMapping("/payment-methods")
    public List<PaymentMethodResponse> paymentMethods() {
        return billingService.paymentMethods();
    }

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

                    **Choosing how to pay.** Send `method` from
                    `GET /api/v1/billing/payment-methods`. Whichever you pick, the
                    purchase comes back `PENDING` and money arrives out of band, so
                    poll `GET /api/v1/billing/purchases` until it reads `COMPLETED` or
                    `FAILED` - every few seconds is enough. Neither method leaves a
                    purchase pending forever: an unanswered prompt and an abandoned
                    card page are both eventually marked `FAILED`.

                    `MOMO` - also send `payerMsisdn`. Comes back
                    `action: PROMPT_ON_PHONE`; a prompt has gone to that handset and
                    nobody has approved it yet.

                    `STRIPE` (alias `CARD`) - comes back `action: REDIRECT` with a
                    `redirectUrl`. Open it in a browser or web view; the payer enters
                    their card on Stripe's own page and is returned to the app by deep
                    link. Returning proves nothing - they may have pressed back - so
                    the purchase is still what to believe.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400",
            description = "It is free, it is your own, `msisdn_required` - Mobile Money "
                    + "needs a `payerMsisdn` - or `unknown_payment_method`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such item",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already owned, or monetisation is off",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`momo_unavailable` or `stripe_unavailable` - the chosen provider is not responding",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/media/{mediaId}")
    public CheckoutResponse unlockMedia(@AuthenticationPrincipal AuthUser principal,
                                        @PathVariable UUID mediaId,
                                        @Valid @RequestBody(required = false) CheckoutRequest request) {
        return billingService.unlockMedia(principal.user(), mediaId, choiceOf(request));
    }

    @Operation(
            summary = "What it costs to open everything this creator has locked",
            description = """
                    The sum of every locked photo and video on that profile, in one figure,
                    so a viewer can see the whole gallery's price before deciding between
                    it and a single item. There is no bundle discount: it costs what buying
                    them one at a time costs and simply takes one payment.

                    **Answered for the caller**, so it shrinks as they buy: somebody who
                    owns two of four clips is quoted for the other two. `itemCount: 0`
                    means there is nothing left to sell them and the offer should not be
                    shown.

                    Public - an anonymous caller owns nothing, so they get the full price
                    of the gallery, which is exactly what a visitor deciding whether to
                    sign up needs to see.
                    """)
    @ApiResponse(responseCode = "200", description = "The bundle price for this caller")
    @GetMapping("/members/{userId}/unlock-all")
    public UnlockAllQuote quoteUnlockAll(@AuthenticationPrincipal AuthUser principal,
                                         @PathVariable UUID userId) {
        return billingService.quoteUnlockAll(AuthUser.userOrNull(principal), userId);
    }

    @Operation(
            summary = "Buy everything this creator has locked, in one payment",
            description = """
                    One purchase covering every locked photo and video on the profile, at
                    the total from `GET /billing/members/{userId}/unlock-all`.

                    **It buys the items that exist now, not the profile forever.** The list
                    is pinned to the purchase when it is created, so anything the creator
                    posts afterwards is bought separately - and a creator who deletes
                    something between checkout and settlement cannot reduce what was paid
                    for. Coming back later and buying again is the top-up for whatever has
                    appeared since.

                    Grants the same per-item access buying them one by one would, so the
                    gallery, the lightbox and the entitlement checks all behave identically
                    afterwards.

                    Paying works exactly as it does for a single item - same `method`, same
                    `payerMsisdn`, same instructions to follow.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400", description = "Your own profile, or `unknown_payment_method`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "`nothing_to_unlock` - you already own everything posted so far",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/members/{userId}/unlock-all")
    public CheckoutResponse unlockAll(@AuthenticationPrincipal AuthUser principal,
                                      @PathVariable UUID userId,
                                      @Valid @RequestBody(required = false) CheckoutRequest request) {
        return billingService.unlockAll(principal.user(), userId, choiceOf(request));
    }

    @Operation(
            summary = "Buy entry to one live broadcast",
            description = """
                    Each stream carries its own access price, set by its host. Buying one
                    grants the playback URL for that stream and nothing else.

                    **Choosing how to pay.** Send `method` from
                    `GET /api/v1/billing/payment-methods`. Whichever you pick, the
                    purchase comes back `PENDING` and money arrives out of band, so
                    poll `GET /api/v1/billing/purchases` until it reads `COMPLETED` or
                    `FAILED` - every few seconds is enough. Neither method leaves a
                    purchase pending forever: an unanswered prompt and an abandoned
                    card page are both eventually marked `FAILED`.

                    `MOMO` - also send `payerMsisdn`. Comes back
                    `action: PROMPT_ON_PHONE`; a prompt has gone to that handset and
                    nobody has approved it yet.

                    `STRIPE` (alias `CARD`) - comes back `action: REDIRECT` with a
                    `redirectUrl`. Open it in a browser or web view; the payer enters
                    their card on Stripe's own page and is returned to the app by deep
                    link. Returning proves nothing - they may have pressed back - so
                    the purchase is still what to believe.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400",
            description = "It is open to everyone, it is your own, `msisdn_required` - "
                    + "Mobile Money needs a `payerMsisdn` - or `unknown_payment_method`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`momo_unavailable` or `stripe_unavailable` - the chosen provider is not responding",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/live/{sessionId}")
    public CheckoutResponse buyLiveAccess(@AuthenticationPrincipal AuthUser principal,
                                          @PathVariable UUID sessionId,
                                          @Valid @RequestBody(required = false) CheckoutRequest request) {
        return billingService.buyLiveAccess(principal.user(), sessionId, choiceOf(request));
    }

    // ------------------------------------------------------------ balance

    @Operation(summary = "My balance, and what a top-up may be",
            description = """
                    The balance gifts are sent from, plus the bounds a top-up has to fall
                    inside and the amounts worth offering as one tap.

                    Balance is not a separate currency - it is the platform's own
                    currency, held on account. It is also spent automatically against any
                    purchase, which is why a package can settle without a payment.
                    """,
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Balance and top-up bounds")
    @GetMapping("/credit")
    public CreditBalanceResponse credit(@AuthenticationPrincipal AuthUser principal) {
        return billingService.creditBalance(principal.user());
    }

    @Operation(
            summary = "Load balance",
            description = """
                    Buys balance rather than content. Settles like any other purchase -
                    the same providers, the same PENDING then COMPLETED - and the balance
                    appears only once the money has actually cleared.

                    Deliberately the one purchase existing balance is **not** applied to:
                    paying for balance with balance would settle a top-up for nothing and
                    hand back what it consumed.

                    Unlike the item purchases, a PENDING top-up is never reused. Somebody
                    who started at 5 000 and came back for 20 000 meant the second figure.
                    """,
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Pay it the way the `action` says")
    @ApiResponse(responseCode = "400",
            description = "`invalid_amount` - outside the bounds - `msisdn_required`, or `unknown_payment_method`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "`topup_disabled`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`momo_unavailable` or `stripe_unavailable`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/credit/top-up")
    public CheckoutResponse topUp(@AuthenticationPrincipal AuthUser principal,
                                  @Valid @RequestBody TopUpRequest request) {
        return billingService.buyCredit(principal.user(), request.amountMinor(),
                PaymentChoice.of(request.method(), request.payerMsisdn()));
    }

    /** The body is optional, so it may not be there at all. */
    private static PaymentChoice choiceOf(CheckoutRequest request) {
        return request == null
                ? PaymentChoice.none()
                : PaymentChoice.of(request.method(), request.payerMsisdn());
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

                    **Choosing how to pay.** Send `method` from
                    `GET /api/v1/billing/payment-methods`. Whichever you pick, the
                    purchase comes back `PENDING` and money arrives out of band, so
                    poll `GET /api/v1/billing/purchases` until it reads `COMPLETED` or
                    `FAILED` - every few seconds is enough. Neither method leaves a
                    purchase pending forever: an unanswered prompt and an abandoned
                    card page are both eventually marked `FAILED`.

                    `MOMO` - also send `payerMsisdn`. Comes back
                    `action: PROMPT_ON_PHONE`; a prompt has gone to that handset and
                    nobody has approved it yet.

                    `STRIPE` (alias `CARD`) - comes back `action: REDIRECT` with a
                    `redirectUrl`. Open it in a browser or web view; the payer enters
                    their card on Stripe's own page and is returned to the app by deep
                    link. Returning proves nothing - they may have pressed back - so
                    the purchase is still what to believe.
                    """)
    @ApiResponse(responseCode = "200", description = "Purchase created; follow the payment instructions")
    @ApiResponse(responseCode = "400",
            description = "Unknown package code, `msisdn_required` - Mobile Money needs a "
                    + "`payerMsisdn` - or `unknown_payment_method`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Creator packages are switched off here",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`momo_unavailable` or `stripe_unavailable` - the chosen provider is not responding",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/creator-packages")
    public CheckoutResponse buyCreatorPackage(@AuthenticationPrincipal AuthUser principal,
                                              @Valid @RequestBody BuyPackageRequest request) {
        return billingService.buyCreatorPackage(principal.user(), request.packageCode(),
                PaymentChoice.of(request.method(), request.payerMsisdn()));
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

    @Operation(
            summary = "One purchase, checked with the provider",
            description = """
                    **What to poll after starting a payment.** Unlike the history list,
                    this asks the payment provider directly when the purchase is still
                    `PENDING`, so a card payment resolves on the first call instead of
                    waiting for a webhook or the periodic sweep.

                    Poll it every second or two after a redirect returns. It is a single
                    row and one upstream call, not a page of history filtered client-side.

                    A `PENDING` answer still means pending - on mobile money the payer has
                    not approved on their handset yet. It never means failed.
                    """)
    @ApiResponse(responseCode = "200", description = "The purchase as it stands right now")
    @ApiResponse(responseCode = "404", description = "No such purchase, or not yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/purchases/{purchaseId}")
    public PurchaseResponse purchase(@AuthenticationPrincipal AuthUser principal,
                                     @PathVariable UUID purchaseId) {
        return billingService.purchase(principal.id(), purchaseId);
    }

    @Operation(summary = "My payment history")
    @ApiResponse(responseCode = "200", description = "Purchases, newest first")
    @GetMapping("/purchases")
    public PageResponse<PurchaseResponse> purchases(@AuthenticationPrincipal AuthUser principal,
                                                    @PageableDefault(size = 20) Pageable pageable) {
        return billingService.history(principal.id(), pageable);
    }
}
