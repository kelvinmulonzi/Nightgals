package com.nightgals.live;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LiveChatMessageRepository extends JpaRepository<LiveChatMessage, UUID> {

    /**
     * Messages sent since the client last asked.
     *
     * <p>Strictly after, so the row that ended the previous page is not replayed
     * as a fresh one - a duplicate here means the same message appearing twice.
     * Ascending, because this is a feed being caught up with and messages should
     * arrive in the order they were sent. See {@link GiftRepository#findSince}.
     */
    @Query("""
            SELECT m FROM LiveChatMessage m
            WHERE m.liveSession.id = :sessionId AND m.createdAt > :since
            ORDER BY m.createdAt ASC""")
    List<LiveChatMessage> findSince(@Param("sessionId") UUID sessionId, @Param("since") Instant since);

    /** The opening page, for a viewer joining a broadcast already in progress. */
    List<LiveChatMessage> findTop50ByLiveSessionIdOrderByCreatedAtDesc(UUID sessionId);
}
