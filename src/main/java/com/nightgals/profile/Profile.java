package com.nightgals.profile;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.Period;

@Entity
@Table(name = "profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Profile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * Optional and private. Other members see the account's username; this is
     * only ever shown back to the owner and to support staff.
     */
    @Column(name = "display_name", length = 50)
    private String displayName;

    @Column(length = 500)
    private String bio;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Gender gender;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Vibe vibe = Vibe.ANYTHING;

    @Column(nullable = false)
    @Builder.Default
    private boolean discoverable = true;

    /**
     * A number this member is choosing to publish, for WhatsApp.
     *
     * <p>Not the same as the handset that pays - that lives on the purchase, and
     * conflating the two would publish a payment detail.
     */
    @Column(length = 20)
    private String whatsappNumber;

    /**
     * The profile picture.
     *
     * <p>Its own field rather than a flag on a gallery item: an avatar is part of
     * the profile, not something published and priced. Keeping it here means
     * setting one does not also post a photo, and changing it does not disturb
     * the gallery.
     */
    @Column(name = "avatar_storage_key", length = 500)
    private String avatarStorageKey;

    @Column(name = "avatar_content_type", length = 100)
    private String avatarContentType;

    public boolean hasAvatar() {
        return avatarStorageKey != null && !avatarStorageKey.isBlank();
    }

    /**
     * Years old, or null when nobody asked.
     *
     * <p>A viewer's profile exists to hold a picture and carries no date of
     * birth, so this has no answer for one. Null rather than a zero: a card that
     * prints "0" is worse than a card that prints nothing.
     */
    public Integer getAge() {
        return dateOfBirth == null ? null : Period.between(dateOfBirth, LocalDate.now()).getYears();
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
