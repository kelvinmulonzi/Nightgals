package com.nightgals.live;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message sent to a broadcast's chat. Rows are never deleted.
 *
 * <p>Same shape as {@link Gift}, for the same reason: a room's feed is
 * ephemeral - nothing shows it once the broadcast has scrolled past - so what
 * is persisted is the whole record, not a cache that could be dropped. Unlike
 * a gift there is no money and nothing to copy from configuration; the only
 * thing worth keeping true to the moment it was sent is the text itself.
 */
@Entity
@Table(name = "live_chat_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "live_session_id", nullable = false)
    private LiveSession liveSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(nullable = false, length = 300)
    private String body;
}
