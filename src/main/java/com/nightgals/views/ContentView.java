package com.nightgals.views;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One person, one item, one day.
 *
 * <p>The ledger behind the counters. Its whole job is the unique index across
 * {@code (subject_type, subject_id, viewer_key, viewed_on)}: without it a
 * refresh is a view and the numbers mean nothing.
 *
 * <p>Not a {@code BaseEntity}: there is no updating a row here, and a
 * {@code createdAt} beside {@code viewedAt} would be two names for one instant.
 */
@Entity
@Table(name = "content_views")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentView {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private ViewSubject subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /**
     * Who looked, as a key rather than a person.
     *
     * <p>The account id when there is one. For a signed-out visitor, a SHA-256 of
     * their address and user agent - enough to tell two people apart for a day,
     * and not enough to identify either of them or to follow anybody across days,
     * since the row it keys is dropped with the rest of that day's ledger.
     */
    @Column(name = "viewer_key", nullable = false, length = 64)
    private String viewerKey;

    @Column(name = "viewer_id")
    private UUID viewerId;

    @Column(name = "viewed_on", nullable = false)
    private LocalDate viewedOn;

    @Column(name = "viewed_at", nullable = false)
    @Builder.Default
    private Instant viewedAt = Instant.now();
}
