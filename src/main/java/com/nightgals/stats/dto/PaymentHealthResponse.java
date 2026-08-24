package com.nightgals.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * How well the money is actually moving.
 *
 * <p>Revenue answers "how much"; this answers "how much of what people tried".
 * A platform can take the same amount two weeks running while quietly failing a
 * third of its attempts, and only this tells you.
 */
@Schema(description = "Payment attempts, outcomes and what went wrong")
public record PaymentHealthResponse(

        @Schema(description = "First day in the window, inclusive") LocalDate from,
        @Schema(description = "Last day in the window, inclusive") LocalDate to,

        @Schema(description = """
                The headline for the window: attempts, how many settled, and the
                rate between them. Rounded to one decimal, because a rate that
                reads 97% when it is 96.5% is the kind of rounding that hides a
                bad week.
                """)
        Summary summary,

        @Schema(description = "One point per day, oldest first, quiet days present as zeroes")
        List<DailyPoint> points,

        @Schema(description = "The same outcomes per provider, busiest first")
        List<ProviderHealth> providers,

        @Schema(description = "Why payments failed, commonest first, at most eight")
        List<FailureReason> failureReasons,

        @Schema(description = "Payments still pending well past the point they should have resolved")
        Stuck stuck) {

    @Schema(description = "Window totals")
    public record Summary(
            long attempts,
            long settled,
            long failed,
            long cancelled,
            long pending,
            @Schema(description = "settled / (settled + failed), as a percentage. Null when nothing was attempted.",
                    example = "96.5")
            Double settleRatePercent) {}

    @Schema(description = "One day")
    public record DailyPoint(
            LocalDate date,
            long settled,
            long failed,
            long cancelled,
            long pending,
            @Schema(description = "Null on a day with no settled or failed attempts - a rate of zero would draw a crash that did not happen")
            Double settleRatePercent) {}

    @Schema(description = "One provider")
    public record ProviderHealth(
            @Schema(example = "STRIPE") String provider,
            long attempts,
            long settled,
            long failed,
            long cancelled,
            long pending,
            Double settleRatePercent) {}

    @Schema(description = "One failure cause")
    public record FailureReason(String reason, long failures) {}

    @Schema(description = "The stuck-payment alarm")
    public record Stuck(
            @Schema(description = "How many are still PENDING past the threshold") long count,
            @Schema(description = "Age of the oldest, in hours") long oldestHours,
            @Schema(description = "The age past which a pending payment is considered stuck", example = "24")
            int thresholdHours) {}
}
