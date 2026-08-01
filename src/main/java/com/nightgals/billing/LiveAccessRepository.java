package com.nightgals.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LiveAccessRepository extends JpaRepository<LiveAccess, UUID> {

    boolean existsByViewerIdAndSessionId(UUID viewerId, UUID sessionId);

    @Query("""
            SELECT a.session.id FROM LiveAccess a
            WHERE a.viewer.id = :viewerId AND a.session.id IN :sessionIds
            """)
    List<UUID> findAccessibleAmong(@Param("viewerId") UUID viewerId,
                                   @Param("sessionIds") List<UUID> sessionIds);
}
