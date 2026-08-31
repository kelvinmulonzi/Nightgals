package com.nightgals.stats;

import com.nightgals.stats.dto.GrowthResponse;
import com.nightgals.stats.dto.PaymentHealthResponse;
import com.nightgals.stats.dto.RevenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.nightgals.stats.dto.AudienceResponse;

/** Shapes the dashboard aggregates for the staff console. */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** Longest window the dashboard will draw, so a stray `?days=100000` cannot scan the table. */
    public static final int MAX_DAYS = 365;

    /**
     * How many creators the audience panel names.
     *
     * <p>Short on purpose. A leaderboard long enough to include everybody is a
     * list nobody reads; the point of this one is the handful at the top and
     * whether their sales agree with their attention.
     */
    private static final int TOP_PROFILES = 10;

    /**
     * How old a still-pending payment has to be before it counts as stuck.
     *
     * <p>A day, because the slowest path here is Mobile Money waiting on a
     * handset prompt, and that is measured in minutes. Anything still open a day
     * later is not slow, it is broken - most often a purchase pointing at a
     * checkout session the account can no longer see.
     */
    private static final int STUCK_AFTER_HOURS = 24;

    /** Shared zero for days nobody signed up; never mutated. */
    private static final long[] EMPTY_DAY = new long[2];

    private final StatsRepository repository;
    private final com.nightgals.views.ContentViewRepository viewRepository;
    private final com.nightgals.profile.ProfileRepository profileRepository;


    /**
     * Views across profiles, videos and reels.
     *
     * <p>Counted from the ledger rather than off the denormalised counters on the
     * rows: those are all-time totals, and every question a dashboard asks is
     * "lately". The counters are for showing a creator her number; this is for
     * seeing which way it is moving.
     *
     * <p>Quiet days are filled with zeroes so the result plots straight, for the
     * same reason the revenue series does: a line that skips a day draws a slope
     * between two points that were never adjacent.
     *
     * @param days how many days back to reach, clamped to 1..{@value #MAX_DAYS}
     */
    @Transactional(readOnly = true)
    public AudienceResponse audience(int days) {
        int span = Math.clamp(days, 1, MAX_DAYS);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(span - 1L);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] row : viewRepository.dailyTotals(from)) {
            LocalDate day = ((java.sql.Date) row[0]).toLocalDate();
            String kind = (String) row[1];
            long count = ((Number) row[2]).longValue();
            long[] slot = byDay.computeIfAbsent(day, d -> new long[3]);
            switch (kind) {
                case "PROFILE" -> slot[0] += count;
                case "MEDIA" -> slot[1] += count;
                case "REEL" -> slot[2] += count;
                default -> { /* a kind this build does not know about; ignore it */ }
            }
        }

        List<AudienceResponse.DailyPoint> points = new ArrayList<>();
        long total = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] slot = byDay.getOrDefault(day, new long[3]);
            long dayTotal = slot[0] + slot[1] + slot[2];
            total += dayTotal;
            points.add(new AudienceResponse.DailyPoint(day, slot[0], slot[1], slot[2], dayTotal));
        }

        List<AudienceResponse.TopProfile> top = new ArrayList<>();
        for (Object[] row : viewRepository.topSince("PROFILE", from, TOP_PROFILES)) {
            UUID userId = (UUID) row[0];
            long views = ((Number) row[1]).longValue();
            profileRepository.findByUserId(userId).ifPresent(profile -> top.add(
                    new AudienceResponse.TopProfile(
                            userId,
                            profile.getUser().getUsername(),
                            profile.getDisplayName(),
                            views,
                            // Beside the views on purpose: the rows worth reading
                            // are the ones where attention and takings disagree.
                            repository.completedSalesFor(userId, from))));
        }

        return new AudienceResponse(from, to, total, points, top);
    }

    /**
     * Settled revenue for the last {@code days} days, ending today.
     *
     * <p>Days are UTC days. The rest of the platform already works that way -
     * live allowances reset on the UTC date - and a dashboard that bucketed on a
     * local zone would disagree with the numbers a creator sees for the same day.
     *
     * @param days how many days back to reach, clamped to 1..{@value #MAX_DAYS}
     */
    @Transactional(readOnly = true)
    public RevenueResponse revenue(int days) {
        int span = Math.clamp(days, 1, MAX_DAYS);

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(span - 1L);

        // Half-open at the top: `to` is inclusive as a day, so the bound is the
        // start of tomorrow. Anything settling while this request runs still counts.
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Currency -> day -> that day's row. Sorted inside so the points come out
        // oldest first without a second pass.
        Map<String, TreeMap<LocalDate, StatsRepository.DailyRevenueRow>> byCurrency = new LinkedHashMap<>();
        for (StatsRepository.DailyRevenueRow row : repository.dailyRevenue(fromInstant, toInstant)) {
            byCurrency
                    .computeIfAbsent(row.getCurrency(), c -> new TreeMap<>())
                    .put(row.getDate(), row);
        }

        List<RevenueResponse.RevenueSeries> series = new ArrayList<>();
        byCurrency.forEach((currency, rows) -> series.add(toSeries(currency, rows, from, to)));

        // Busiest currency first, so a client that draws only the first series
        // draws the one that matters.
        series.sort(Comparator.comparingLong(RevenueResponse.RevenueSeries::cashMinor).reversed());

        return new RevenueResponse(from, to, List.copyOf(series));
    }

    /**
     * Signups for the last {@code days} days, plus the all-time funnel.
     *
     * <p>The window applies to the daily line only. The funnel is deliberately
     * unwindowed - see {@link StatsRepository#funnel()}.
     */
    @Transactional(readOnly = true)
    public GrowthResponse growth(int days) {
        int span = Math.clamp(days, 1, MAX_DAYS);

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(span - 1L);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // day -> [viewers, creators]. A TreeMap so the fill below can look each
        // day up rather than scanning.
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (StatsRepository.DailySignupRow row : repository.dailySignups(fromInstant, toInstant)) {
            long[] slot = byDay.computeIfAbsent(row.getDate(), d -> new long[2]);
            if ("CREATOR".equals(row.getAccountType())) {
                slot[1] += row.getSignups();
            } else {
                slot[0] += row.getSignups();
            }
        }

        List<GrowthResponse.SignupPoint> points = new ArrayList<>();
        long total = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] slot = byDay.getOrDefault(day, EMPTY_DAY);
            points.add(new GrowthResponse.SignupPoint(day, slot[0], slot[1], slot[0] + slot[1]));
            total += slot[0] + slot[1];
        }

        StatsRepository.FunnelRow f = repository.creatorFunnel();
        StatsRepository.MixRow m = repository.mix();

        // Widest first. The client renders these in order and relies on them
        // narrowing, so the order is part of the contract rather than a detail.
        List<GrowthResponse.FunnelStage> funnel = List.of(
                new GrowthResponse.FunnelStage("REGISTERED", f.getRegistered()),
                new GrowthResponse.FunnelStage("IDENTITY_SUBMITTED", f.getIdentitySubmitted()),
                new GrowthResponse.FunnelStage("IDENTITY_APPROVED", f.getIdentityApproved()),
                new GrowthResponse.FunnelStage("PUBLISHING", f.getPublishing()));

        return new GrowthResponse(
                from,
                to,
                total,
                List.copyOf(points),
                funnel,
                new GrowthResponse.Mix(
                        m.getViewers(),
                        m.getCreators(),
                        m.getViaGoogle(),
                        m.getEmailVerified(),
                        m.getPayingViewers()));
    }

    /**
     * Widens one currency's rows into a continuous daily run.
     *
     * <p>Every day in the window gets a point, quiet ones included. A chart drawn
     * from a sparse array spaces its x-axis by row rather than by date, which
     * turns a fortnight of silence into a step the same width as a single day and
     * misreads flat as busy.
     */
    /**
     * How many payment attempts got through, for the last {@code days} days.
     *
     * <p>Windowed on when a payment was <em>started</em>, unlike revenue, which
     * is dated by settlement. The question here is what share of attempts
     * succeeded, and a failure only belongs to the day somebody tried.
     *
     * @param days how many days back to reach, clamped to 1..{@value #MAX_DAYS}
     */
    @Transactional(readOnly = true)
    public PaymentHealthResponse paymentHealth(int days) {
        int span = Math.clamp(days, 1, MAX_DAYS);

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(span - 1L);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, StatsRepository.DailyOutcomeRow> byDay = new TreeMap<>();
        for (StatsRepository.DailyOutcomeRow row : repository.dailyOutcomes(fromInstant, toInstant)) {
            byDay.put(row.getDate(), row);
        }

        // Every day in the window, present or not, so the client plots the array
        // as it stands rather than reconstructing the calendar.
        List<PaymentHealthResponse.DailyPoint> points = new ArrayList<>();
        long settled = 0, failed = 0, cancelled = 0, pending = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            StatsRepository.DailyOutcomeRow row = byDay.get(day);
            long s = row == null ? 0 : row.getSettled();
            long f = row == null ? 0 : row.getFailed();
            long c = row == null ? 0 : row.getCancelled();
            long p = row == null ? 0 : row.getPending();
            settled += s; failed += f; cancelled += c; pending += p;
            points.add(new PaymentHealthResponse.DailyPoint(day, s, f, c, p, rate(s, f)));
        }

        List<PaymentHealthResponse.ProviderHealth> providers = new ArrayList<>();
        for (StatsRepository.ProviderOutcomeRow row : repository.providerOutcomes(fromInstant, toInstant)) {
            long attempts = row.getSettled() + row.getFailed() + row.getCancelled() + row.getPending();
            providers.add(new PaymentHealthResponse.ProviderHealth(
                    row.getProvider(), attempts, row.getSettled(), row.getFailed(),
                    row.getCancelled(), row.getPending(), rate(row.getSettled(), row.getFailed())));
        }

        List<PaymentHealthResponse.FailureReason> reasons = repository
                .failureReasons(fromInstant, toInstant).stream()
                .map(r -> new PaymentHealthResponse.FailureReason(r.getReason(), r.getFailures()))
                .toList();

        StatsRepository.StuckRow stuckRow = repository.stuckPending(
                Instant.now().minus(Duration.ofHours(STUCK_AFTER_HOURS)));
        var stuck = new PaymentHealthResponse.Stuck(
                stuckRow == null ? 0 : stuckRow.getCount(),
                stuckRow == null ? 0 : stuckRow.getOldestHours(),
                STUCK_AFTER_HOURS);

        var summary = new PaymentHealthResponse.Summary(
                settled + failed + cancelled + pending,
                settled, failed, cancelled, pending, rate(settled, failed));

        return new PaymentHealthResponse(
                from, to, summary, List.copyOf(points), List.copyOf(providers), reasons, stuck);
    }

    /**
     * Settled as a share of everything that resolved one way or the other.
     *
     * <p>Cancellations are left out of both halves on purpose: somebody backing
     * out of a card form did not fail, and counting it as one would make a
     * hesitant week look like an outage. Pending is left out too - it has not
     * finished, and guessing which way it will go is how a rate becomes fiction.
     *
     * <p>Null rather than zero when nothing resolved. A quiet day drawn as 0%
     * is a crash that never happened.
     */
    private static Double rate(long settled, long failed) {
        long resolved = settled + failed;
        if (resolved == 0) {
            return null;
        }
        return Math.round(settled * 1000.0 / resolved) / 10.0;
    }

    private RevenueResponse.RevenueSeries toSeries(
            String currency,
            TreeMap<LocalDate, StatsRepository.DailyRevenueRow> rows,
            LocalDate from,
            LocalDate to) {

        List<RevenueResponse.RevenuePoint> points = new ArrayList<>();
        long cash = 0;
        long gross = 0;
        long orders = 0;

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            StatsRepository.DailyRevenueRow row = rows.get(day);
            long dayCash = row == null ? 0 : row.getCashMinor();
            long dayGross = row == null ? 0 : row.getGrossMinor();
            long dayOrders = row == null ? 0 : row.getOrders();

            points.add(new RevenueResponse.RevenuePoint(day, dayCash, dayGross, dayOrders));
            cash += dayCash;
            gross += dayGross;
            orders += dayOrders;
        }

        return new RevenueResponse.RevenueSeries(currency, cash, gross, orders, List.copyOf(points));
    }
}
