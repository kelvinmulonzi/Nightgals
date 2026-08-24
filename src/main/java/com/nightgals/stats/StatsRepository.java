package com.nightgals.stats;

import com.nightgals.billing.Purchase;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregates for the staff console.
 *
 * <p>Deliberately not a {@code JpaRepository}: nothing here writes, and the
 * dashboard has no business being handed {@code save} or {@code delete} on
 * purchases. Extending the marker interface exposes exactly these queries.
 *
 * <p>The aggregation runs in the database rather than over a fetched list.
 * Thirty days of rows is nothing today, but a dashboard that pulls every
 * purchase into memory to add it up is a query that gets slower every week the
 * platform succeeds.
 */
public interface StatsRepository extends Repository<Purchase, UUID> {

    /**
     * Settled money per day per currency.
     *
     * <p>Native rather than JPQL because the bucketing is a date cast, which JPQL
     * has no portable way to express. Aliases are quoted so Postgres preserves
     * their case and they line up with the projection's getters.
     *
     * <p>Dated by {@code completed_at} - when the money actually landed - and not
     * by {@code created_at}, which is when someone opened a payment they may never
     * have finished. Rows settled before that column existed fall back to
     * {@code created_at} so old takings still appear rather than vanishing.
     *
     * <p>{@code amount_minor - credit_applied_minor} is cash, not the sticker
     * price: credit was already collected as a {@code CREDIT_TOPUP}, so counting
     * the full price of something bought with a balance would bank the same money
     * twice.
     *
     * <p>The sums are cast back to {@code bigint} because Postgres widens
     * {@code SUM(bigint)} to {@code numeric}, which arrives as a {@code BigDecimal}
     * and will not bind to a {@code long}.
     */
    @Query(value = """
            SELECT CAST(COALESCE(p.completed_at, p.created_at) AS date)      AS "date",
                   p.currency                                               AS "currency",
                   CAST(SUM(p.amount_minor - p.credit_applied_minor) AS bigint) AS "cashMinor",
                   CAST(SUM(p.amount_minor) AS bigint)                      AS "grossMinor",
                   COUNT(*)                                                 AS "orders"
            FROM purchases p
            WHERE p.status = 'COMPLETED'
              AND COALESCE(p.completed_at, p.created_at) >= :from
              AND COALESCE(p.completed_at, p.created_at) < :to
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<DailyRevenueRow> dailyRevenue(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Signups per day, split by what the account is for.
     *
     * <p>Dated by {@code created_at}: registering is the event, and unlike a
     * payment there is no later moment when it becomes real.
     *
     * <p>Suspended and deactivated accounts are counted. They signed up, and a
     * growth chart that quietly removed them would rewrite history every time a
     * moderator acted - yesterday's number would change overnight.
     *
     * <p>Staff are not counted, matching {@link #funnel()}. A seeded
     * administrator is not growth, and counting it here while the funnel beside
     * it excludes the same account would leave the two panels disagreeing about
     * how many people signed up.
     */
    @Query(value = """
            SELECT CAST(u.created_at AS date) AS "date",
                   u.account_type            AS "accountType",
                   COUNT(*)                  AS "signups"
            FROM users u
            WHERE u.created_at >= :from AND u.created_at < :to
              AND u.role = 'USER'
            GROUP BY 1, 2
            ORDER BY 1
            """, nativeQuery = true)
    List<DailySignupRow> dailySignups(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * How far creator accounts get, all-time.
     *
     * <p>Scoped to creators because they are the only accounts with a pipeline.
     * A viewer's onboarding is "browse" and stops there - it has no steps - so a
     * funnel drawn over every account would report viewers dropping out of
     * stages they were never asked to enter.
     *
     * <p>Each stage repeats the conditions of the ones before it, so the counts
     * narrow because of how they are written rather than because the data
     * happens to cooperate. Counting the flags independently does not work here:
     * identity review does not require a verified email, so an "approved" count
     * can legitimately exceed an "email verified" one and the bars stop being a
     * funnel.
     *
     * <p>Not windowed: a creator who registers today may be approved next week,
     * and a thirty-day funnel would report that as a drop-out.
     */
    @Query(value = """
            SELECT COUNT(*)                                                      AS "registered",
                   COUNT(*) FILTER (WHERE u.verification_status <> 'UNVERIFIED') AS "identitySubmitted",
                   COUNT(*) FILTER (WHERE u.verification_status = 'APPROVED')    AS "identityApproved",
                   COUNT(*) FILTER (WHERE u.verification_status = 'APPROVED' AND EXISTS (
                       SELECT 1 FROM purchases p
                       WHERE p.user_id = u.id
                         AND p.status = 'COMPLETED'
                         AND p.type = 'CREATOR_PACKAGE'))                        AS "publishing"
            FROM users u
            WHERE u.role = 'USER' AND u.account_type = 'CREATOR'
            """, nativeQuery = true)
    FunnelRow creatorFunnel();

    /**
     * Composition, all-time. Not stages - see {@link #creatorFunnel()}.
     *
     * <p>{@code payingViewers} is the viewer-side conversion the creator funnel
     * cannot show: viewers have no pipeline, so the only question worth asking
     * about them is whether they ever paid for anything.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE u.account_type = 'VIEWER')   AS "viewers",
                   COUNT(*) FILTER (WHERE u.account_type = 'CREATOR')  AS "creators",
                   COUNT(*) FILTER (WHERE u.google_subject IS NOT NULL) AS "viaGoogle",
                   COUNT(*) FILTER (WHERE u.email_verified)            AS "emailVerified",
                   COUNT(*) FILTER (WHERE u.account_type = 'VIEWER' AND EXISTS (
                       SELECT 1 FROM purchases p
                       WHERE p.user_id = u.id AND p.status = 'COMPLETED')) AS "payingViewers"
            FROM users u
            WHERE u.role = 'USER'
            """, nativeQuery = true)
    MixRow mix();

    /** One day of one account type. */
    interface DailySignupRow {
        LocalDate getDate();

        String getAccountType();

        long getSignups();
    }

    /** The creator pipeline, one row. Stages narrow by construction. */
    interface FunnelRow {
        long getRegistered();

        long getIdentitySubmitted();

        long getIdentityApproved();

        long getPublishing();
    }

    /** Account composition, one row. */
    interface MixRow {
        long getViewers();

        long getCreators();

        long getViaGoogle();

        long getEmailVerified();

        long getPayingViewers();
    }

    /** One day of one currency, straight off the result set. */
    interface DailyRevenueRow {
        LocalDate getDate();

        String getCurrency();

        long getCashMinor();

        long getGrossMinor();

        long getOrders();
    }
}
