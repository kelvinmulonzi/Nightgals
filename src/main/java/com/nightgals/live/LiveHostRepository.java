package com.nightgals.live;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveHostRepository extends JpaRepository<LiveHost, UUID> {

    @Query("""
            SELECT h FROM LiveHost h JOIN FETCH h.user
            WHERE h.session.id = :sessionId
            ORDER BY h.role ASC, h.createdAt ASC
            """)
    List<LiveHost> findRoster(@Param("sessionId") UUID sessionId);

    Optional<LiveHost> findBySessionIdAndUserId(UUID sessionId, UUID userId);

    /** What this creator has been asked to co-host and not yet answered. */
    @Query("""
            SELECT h FROM LiveHost h JOIN FETCH h.session s JOIN FETCH s.host
            WHERE h.user.id = :userId AND h.status = :status
            ORDER BY h.createdAt DESC
            """)
    List<LiveHost> findByUser(@Param("userId") UUID userId, @Param("status") HostStatus status);

    /** The roster for a page of sessions, so a listing is one query not N. */
    @Query("""
            SELECT h FROM LiveHost h JOIN FETCH h.user
            WHERE h.session.id IN :sessionIds AND h.status = com.nightgals.live.HostStatus.ACCEPTED
            """)
    List<LiveHost> findAcceptedForSessions(@Param("sessionIds") List<UUID> sessionIds);
}
