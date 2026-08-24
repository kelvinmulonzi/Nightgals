package com.nightgals.stats;

import com.nightgals.common.ErrorResponse;
import com.nightgals.stats.dto.GrowthResponse;
import com.nightgals.stats.dto.PaymentHealthResponse;
import com.nightgals.stats.dto.RevenueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "14. Dashboards (admin)", description = """
        Aggregate figures for the staff console.

        `ADMIN` only, unlike the review queues. A moderator works the KYC and media
        desks and has no reason to see what the platform takes - the existing manual
        settlement desk is gated the same way, and this keeps the console's two
        halves consistent.

        Everything here is read-only and derived; nothing on these paths changes a
        record.
        """)
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StatsAdminController {

    private final StatsService statsService;

    @Operation(
            summary = "Settled revenue, by day",
            description = """
                    A daily run of what actually settled, split by currency, with quiet
                    days present as zeroes so the result plots directly.

                    `cashMinor` is money the providers moved. `grossMinor` is what the
                    same purchases cost at sticker price - the two differ by whatever was
                    paid out of credit, which was collected earlier as a top-up and must
                    not be banked a second time.

                    Purchases are dated by when they settled, not when they were started,
                    so a payment opened on Monday and confirmed on Tuesday belongs to
                    Tuesday.
                    """)
    @ApiResponse(responseCode = "200", description = "One series per currency, newest day last")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/revenue")
    public RevenueResponse revenue(
            @Parameter(description = "How many days back to reach, including today. Clamped to 1-365.")
            @RequestParam(defaultValue = "30") int days) {
        return statsService.revenue(days);
    }

    @Operation(
            summary = "Payment attempts, outcomes and what went wrong",
            description = """
                    What share of attempted payments actually got through, day by day
                    and per provider, plus the commonest failure causes and a count of
                    payments still pending long after they should have resolved.

                    Revenue says how much came in. This says how much of what people
                    tried came in - a platform can take the same money two weeks running
                    while quietly failing a third of its attempts, and only this shows it.

                    Windowed on when a payment was **started**, unlike revenue, which is
                    dated by settlement: a failure belongs to the day somebody tried.

                    `settleRatePercent` is settled over settled-plus-failed. Cancellations
                    are excluded from both halves - backing out of a card form is not a
                    failure - and it is null rather than zero where nothing resolved, so a
                    quiet day is not drawn as an outage.
                    """)
    @ApiResponse(responseCode = "200", description = "Daily outcomes, provider split, failure causes and the stuck count")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/payments")
    public PaymentHealthResponse payments(
            @Parameter(description = "How many days back to reach, including today. Clamped to 1-365.")
            @RequestParam(defaultValue = "30") int days) {
        return statsService.paymentHealth(days);
    }

    @Operation(
            summary = "Signups and the onboarding funnel",
            description = """
                    Two things read together: a daily signup line for the window, split
                    into viewers and creators, and the all-time funnel from registering
                    to paying.

                    The funnel is not windowed. It counts how many accounts ever reached
                    each step, because the later steps of a cohort that signed up this
                    month mostly happen after it.

                    Suspended and deactivated accounts still count as signups - they did
                    sign up, and dropping them would rewrite past days whenever a
                    moderator acted.
                    """)
    @ApiResponse(responseCode = "200", description = "Daily signups, the funnel, and the account mix")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/growth")
    public GrowthResponse growth(
            @Parameter(description = "How many days back the signup line reaches, including today. Clamped to 1-365.")
            @RequestParam(defaultValue = "30") int days) {
        return statsService.growth(days);
    }
}
