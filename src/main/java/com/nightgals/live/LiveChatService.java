package com.nightgals.live;

import com.nightgals.common.ApiException;
import com.nightgals.live.dto.ChatFeedResponse;
import com.nightgals.live.dto.ChatMessageResponse;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Text chat alongside a broadcast.
 *
 * <p>Shaped like {@link GiftService}'s feed on purpose - a viewer's client
 * already polls one broadcast-scoped, append-only log every few seconds, so
 * chat is a second instance of the same idea rather than a new transport. The
 * two are kept as separate endpoints and separate tables regardless: a gift is
 * a financial record that must outlive the broadcast for receipts and
 * earnings, and chat is not one, so there is no reason to force them through
 * the same shape indefinitely just because they start out identical.
 *
 * <p><b>Access is exactly {@link LiveSessionService#watch}'s, not looser.</b>
 * Every session here is paid to join, and unlike the gift ticker - which is
 * deliberately public, closer to marketing than to room content - chat is
 * something said inside the room. Reusing {@link LiveSessionService#requireJoinable}
 * for both reading and sending means the two can never drift into "you can read
 * what people are saying without having paid to be there."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveChatService {

    private final LiveChatMessageRepository chatRepository;
    private final LiveSessionService liveSessionService;

    /**
     * Posts one message.
     *
     * <p>Ended broadcasts refuse new messages, the same rule {@link
     * GiftService#send} applies: the audience the sender is talking to no longer
     * exists.
     */
    @Transactional
    public ChatMessageResponse send(User sender, UUID sessionId, String body) {
        LiveSession session = liveSessionService.requireJoinable(sessionId, sender);

        if (!session.isLive()) {
            throw ApiException.conflict("not_live",
                    "This broadcast is not live, so chat is closed");
        }

        LiveChatMessage message = chatRepository.save(LiveChatMessage.builder()
                .liveSession(session)
                .sender(sender)
                .body(body.trim())
                .build());

        return ChatMessageResponse.of(message);
    }

    /**
     * What has been said, for a client catching up.
     *
     * <p>Same contract as {@link GiftService#feed}: {@code since} absent means "I
     * have just joined" and returns the last 50 messages rather than the whole
     * history of a long broadcast, and the response carries the server's own
     * clock as {@code until} so a quiet room does not stall a client at an old
     * timestamp.
     */
    @Transactional(readOnly = true)
    public ChatFeedResponse feed(UUID sessionId, User viewer, Instant since) {
        liveSessionService.requireJoinable(sessionId, viewer);
        Instant until = Instant.now();

        var found = new ArrayList<LiveChatMessage>();
        if (since == null) {
            found.addAll(chatRepository.findTop50ByLiveSessionIdOrderByCreatedAtDesc(sessionId));
            // Fetched newest-first to get the last 50, delivered oldest-first
            // because that is the order they were sent in.
            java.util.Collections.reverse(found);
        } else {
            found.addAll(chatRepository.findSince(sessionId, since));
        }

        return new ChatFeedResponse(found.stream().map(ChatMessageResponse::of).toList(), until);
    }
}
