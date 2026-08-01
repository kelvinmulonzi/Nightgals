package com.nightgals.social;

import com.nightgals.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One member following a creator.
 *
 * <p>Exists because the brief asks for reminders: "scheduled live events will
 * appear on their profile so followers can receive reminders". There were no
 * followers, and a reminder needs somebody to remind.
 *
 * <p>Deliberately one-directional and needing no approval. Following is a
 * subscription to somebody's schedule, not a relationship.
 */
@Entity
@Table(name = "follows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_id", nullable = false)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** A follower can keep the follow and drop the mail. */
    @Column(nullable = false)
    @Builder.Default
    private boolean remind = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
