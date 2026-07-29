package com.nightgals.earnings;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.earnings.dto.EarningResponse;
import com.nightgals.earnings.dto.PayoutResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "12. Payouts (admin)", description = """
        Paying creators.

        There is no automated disbursement: an administrator sends the money and
        records the transaction reference here. The queue is the day's work.

        **The routine**

        1. `GET /queue` - who is waiting, how much, and the account to send to.
           This is the only place the full destination number is shown; creators
           see their own masked.
        2. Check `accountName` against the name on their verified ID.
        3. Send the money by M-Pesa or bank transfer.
        4. `POST /{id}/paid?reference=…` with the transaction code. The creator's
           reserved ledger entries close to `PAID`.

        Rejecting instead returns the reserved entries to the creator's available
        balance, so nothing is ever stranded.

        **Subscription revenue** is not attributed automatically - run
        `POST /distribute?period=yyyy-MM` after a month closes to split each
        subscriber's payment among the creators they viewed.
        """)
@RestController
@RequestMapping("/api/v1/admin/payouts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class PayoutAdminController {

    private final PayoutService payoutService;
    private final EarningsService earningsService;
    private final MonetizationProperties monetizationProperties;

    @Operation(summary = "The payout queue",
            description = "Requested and approved payouts, oldest first. Shows the full destination account.")
    @ApiResponse(responseCode = "200", description = "Payouts awaiting action")
    @GetMapping("/queue")
    public PageResponse<PayoutResponse> queue(@PageableDefault(size = 20) Pageable pageable) {
        return payoutService.queue(pageable);
    }

    @Operation(summary = "How many payouts are waiting")
    @ApiResponse(responseCode = "200", description = "Queue count")
    @GetMapping("/queue/count")
    public Map<String, Long> queueCount() {
        return Map.of("requested", payoutService.queueCount());
    }

    @Operation(summary = "Approve a payout",
            description = "Optional intermediate step - records that you intend to pay, before the money moves.")
    @ApiResponse(responseCode = "200", description = "Approved")
    @ApiResponse(responseCode = "409", description = "Not in REQUESTED state",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{payoutId}/approve")
    public PayoutResponse approve(@PathVariable UUID payoutId,
                                  @AuthenticationPrincipal AuthUser principal) {
        return payoutService.approve(payoutId, principal.user());
    }

    @Operation(summary = "Record that the money has been sent",
            description = """
                    Closes the payout and moves the creator's reserved ledger entries to `PAID`.
                    The reference is required - a payment nobody can trace is a dispute waiting
                    to happen.
                    """)
    @ApiResponse(responseCode = "200", description = "Marked paid")
    @ApiResponse(responseCode = "400", description = "No transaction reference given",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{payoutId}/paid")
    public PayoutResponse markPaid(@PathVariable UUID payoutId,
                                   @Parameter(description = "M-Pesa code or bank reference", required = true)
                                   @RequestParam String reference,
                                   @AuthenticationPrincipal AuthUser principal) {
        return payoutService.markPaid(payoutId, reference, principal.user());
    }

    @Operation(summary = "Reject a payout",
            description = "Returns the reserved earnings to the creator's available balance.")
    @ApiResponse(responseCode = "200", description = "Rejected and funds returned")
    @PostMapping("/{payoutId}/reject")
    public PayoutResponse reject(@PathVariable UUID payoutId,
                                 @RequestParam String reason,
                                 @AuthenticationPrincipal AuthUser principal) {
        return payoutService.reject(payoutId, reason, principal.user());
    }

    @Operation(summary = "Platform money summary",
            description = "Commission earned, what is owed to creators, and what has been paid out.")
    @ApiResponse(responseCode = "200", description = "Totals, in minor units")
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return payoutService.platformSummary();
    }

    @Operation(summary = "Attribute a month's subscription revenue",
            description = """
                    Splits each subscription payment settled in the period among the creators
                    that subscriber viewed during it. Run after the month closes.

                    Idempotent per (purchase, creator, period): re-running credits creators
                    newly viewed since the last run without paying anyone twice. A subscriber
                    who viewed nobody contributes nothing - the platform keeps it.
                    """)
    @ApiResponse(responseCode = "200", description = "Number of ledger entries created")
    @ApiResponse(responseCode = "400", description = "Period is not yyyy-MM",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/distribute")
    public Map<String, Object> distribute(
            @Parameter(description = "Month to attribute, yyyy-MM", example = "2026-07")
            @RequestParam String period) {
        int created = earningsService.distributeSubscriptionRevenue(period);
        return Map.of("period", period, "entriesCreated", created);
    }

    @Operation(summary = "Credit or debit a creator by hand",
            description = """
                    For refunds, goodwill, corrections and clawbacks. A negative amount debits.
                    Immediately available - an adjustment is already a decision, so it does not
                    sit in the hold period. The note is required and appears on the creator's ledger.
                    """)
    @ApiResponse(responseCode = "200", description = "Adjustment recorded")
    @PostMapping("/adjustments")
    public EarningResponse adjust(
            @Parameter(description = "Whose ledger to adjust", required = true) @RequestParam UUID creatorId,
            @Parameter(description = "Minor units; negative to debit", example = "5000", required = true)
            @RequestParam long netMinor,
            @Parameter(description = "Why. Shown to the creator.", required = true) @RequestParam String note) {
        return EarningResponse.of(
                earningsService.adjust(creatorId, netMinor, note, monetizationProperties.currency()));
    }
}
