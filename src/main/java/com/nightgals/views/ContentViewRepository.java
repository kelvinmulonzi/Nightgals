package com.nightgals.views;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ContentViewRepository extends JpaRepository<ContentView, UUID> {

    /**
     * Records a look, or does nothing because it is the same person again today.
     *
     * <p>Native, and {@code ON CONFLICT DO NOTHING} rather than a select-then-insert.
     * The check and the write are one statement, so two requests arriving together
     * cannot both decide the row is missing - which on a popular profile is not a
     * rare race but the normal case.
     *
     * @return 1 when this was a new viewer for the day, 0 when it was not - which
     *         is exactly the number the counter should go up by
     */
    @Modifying
    @Query(value = """
            INSERT INTO content_views (id, subject_type, subject_id, viewer_key, viewer_id, viewed_on, viewed_at)
            VALUES (gen_random_uuid(), :subjectType, :subjectId, :viewerKey, :viewerId, :day, NOW())
            ON CONFLICT (subject_type, subject_id, viewer_key, viewed_on) DO NOTHING
            """, nativeQuery = true)
    int recordOnce(@Param("subjectType") String subjectType,
                   @Param("subjectId") UUID subjectId,
                   @Param("viewerKey") String viewerKey,
                   @Param("viewerId") UUID viewerId,
                   @Param("day") LocalDate day);

    /** Bumps the counter on whichever table holds the subject. */
    @Modifying
    @Query(value = "UPDATE profiles SET view_count = view_count + 1 WHERE user_id = :id", nativeQuery = true)
    int bumpProfile(@Param("id") UUID userId);

    @Modifying
    @Query(value = "UPDATE media_assets SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
    int bumpMedia(@Param("id") UUID mediaId);

    @Modifying
    @Query(value = "UPDATE reels SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
    int bumpReel(@Param("id") UUID reelId);

    /**
     * The most-looked-at items over a window, for the staff dashboard.
     *
     * <p>Counted from the ledger rather than read off the counters, because the
     * counters are all-time and the question here is "lately".
     */
    @Query(value = """
            SELECT v.subject_id, COUNT(*) AS views
            FROM content_views v
            WHERE v.subject_type = :subjectType
              AND v.viewed_on >= :from
            GROUP BY v.subject_id
            ORDER BY views DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> topSince(@Param("subjectType") String subjectType,
                            @Param("from") LocalDate from,
                            @Param("limit") int limit);

    /** Views per day across everything, for the dashboard's trend line. */
    @Query(value = """
            SELECT v.viewed_on, v.subject_type, COUNT(*) AS views
            FROM content_views v
            WHERE v.viewed_on >= :from
            GROUP BY v.viewed_on, v.subject_type
            ORDER BY v.viewed_on
            """, nativeQuery = true)
    List<Object[]> dailyTotals(@Param("from") LocalDate from);

    long countByViewedOnGreaterThanEqual(LocalDate from);
}
