package com.nightgals.media;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "media_assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAsset extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MediaType type;

    /** Free content is the shop window; exclusive content is what viewers pay for. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ContentTier tier = ContentTier.EXCLUSIVE;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(length = 300)
    private String caption;

    @Column(nullable = false)
    @Builder.Default
    private int position = 0;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean primary = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MediaStatus status = MediaStatus.PENDING_REVIEW;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    /**
     * What a viewer pays for this one item, in minor units.
     *
     * <p>Set by the creator, per item. Null falls back to the platform default,
     * so something she never priced is still sellable. Meaningless on a FREE
     * item, which is the shop window and is never charged for.
     */
    @Column(name = "unlock_price_minor")
    private Long unlockPriceMinor;

    public boolean isVisibleToOthers() {
        return status == MediaStatus.APPROVED;
    }

    public boolean isFree() {
        return tier == ContentTier.FREE;
    }

    /**
     * How many people have looked at this, all time.
     *
     * <p>Denormalised on purpose. The alternative is a COUNT(*) over the view
     * ledger every time this row is read, which on a page of twenty cards is
     * twenty counts to render one number each. Kept honest by the ledger's
     * unique index rather than by whoever writes to it - see
     * {@link com.nightgals.views.ViewCounterService}.
     */
    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private long viewCount = 0L;
}
