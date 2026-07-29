package com.nightgals.earnings;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.earnings.dto.EarningResponse;
import com.nightgals.earnings.dto.EarningsSummaryResponse;
import com.nightgals.earnings.dto.PayoutAccountRequest;
import com.nightgals.earnings.dto.PayoutAccountResponse;
import com.nightgals.earnings.dto.PayoutResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "11. Creator earnings", description = """
        What a creator has earned, and asking to be paid.

        **How earnings are attributed**

        - **Unlock**: a viewer paid to unlock this creator specifically, so the whole
          net of that payment goes to them.
        - **Subscription share**: a subscriber's payment is split among the creators
          *that subscriber actually viewed* during the month. Viewing somebody fifty
          times counts once - it is the breadth of a subscriber's attention that
          divides their payment, not the volume.

        **The ledger**

        Earnings are append-only lines, never a running total. Each entry moves
        `PENDING` → `AVAILABLE` → `RESERVED` → `PAID`. The hold period keeps new
        earnings unpayable for a while so a refund can still reverse them, and
        `RESERVED` is what stops the same balance being paid out twice.
        """)
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class EarningsController {

    private final PayoutService payoutService;

    @Operation(summary = "My earnings at a glance",
            description = "Available, on hold, reserved against an open payout, and paid to date.")
    @ApiResponse(responseCode = "200", description = "Earnings summary")
    @GetMapping("/earnings")
    public EarningsSummaryResponse summary(@AuthenticationPrincipal AuthUser principal) {
        return payoutService.summary(principal.user());
    }

    @Operation(summary = "My earnings ledger",
            description = "Every line, newest first, with the gross the viewer paid and the commission taken.")
    @ApiResponse(responseCode = "200", description = "Ledger entries")
    @GetMapping("/earnings/entries")
    public PageResponse<EarningResponse> ledger(@AuthenticationPrincipal AuthUser principal,
                                                @PageableDefault(size = 20) Pageable pageable) {
        return payoutService.ledger(principal.id(), pageable);
    }

    @Operation(summary = "Set where my money should be sent",
            description = """
                    M-Pesa number or bank account. Cannot be changed while a payout is being
                    processed, because that would send money to a destination no administrator
                    checked.
                    """)
    @ApiResponse(responseCode = "200", description = "Payout account saved")
    @ApiResponse(responseCode = "409", description = "A payout is currently being processed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/payout-account")
    public PayoutAccountResponse saveAccount(@AuthenticationPrincipal AuthUser principal,
                                             @Valid @RequestBody PayoutAccountRequest request) {
        return PayoutAccountResponse.of(payoutService.saveAccount(principal.user(), request));
    }

    @Operation(summary = "My payout account", description = "The destination comes back masked.")
    @ApiResponse(responseCode = "200", description = "Payout account")
    @ApiResponse(responseCode = "404", description = "No payout account set yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/payout-account")
    public PayoutAccountResponse getAccount(@AuthenticationPrincipal AuthUser principal) {
        return PayoutAccountResponse.of(payoutService.getAccount(principal.id()));
    }

    @Operation(summary = "Request a payout",
            description = """
                    Asks for the whole available balance. The matching ledger entries move to
                    `RESERVED` so they cannot be requested again, and an administrator picks it
                    up from the payout queue.

                    Only one payout can be in flight at a time.
                    """)
    @ApiResponse(responseCode = "200", description = "Payout requested")
    @ApiResponse(responseCode = "400", description = "Balance below the minimum, or no payout account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A payout is already being processed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/payouts")
    public PayoutResponse requestPayout(@AuthenticationPrincipal AuthUser principal) {
        return payoutService.requestPayout(principal.user());
    }

    @Operation(summary = "My payout history")
    @ApiResponse(responseCode = "200", description = "Payouts, newest first")
    @GetMapping("/payouts")
    public PageResponse<PayoutResponse> payouts(@AuthenticationPrincipal AuthUser principal,
                                                @PageableDefault(size = 20) Pageable pageable) {
        return payoutService.creatorPayouts(principal.id(), pageable);
    }
}
