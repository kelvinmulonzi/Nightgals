package com.nightgals.profile;

import com.nightgals.common.ApiException;
import com.nightgals.config.AppProperties;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.profile.dto.ProfileResponse;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public ProfileResponse getOwn(UUID userId) {
        return ProfileResponse.of(requireProfile(userId));
    }

    /**
     * Creates the profile on first call and updates it afterwards, so the client
     * does not have to track which one it needs.
     */
    @Transactional
    public ProfileResponse createOrUpdate(User user, ProfileRequest request) {
        // Submitting a creator profile is the intent, so treat it as such rather
        // than bouncing the caller to /become-creator and back. A viewer who never
        // touches this endpoint stays a viewer.
        User account = upgradeToCreatorIfNeeded(user);
        requireAdult(request.dateOfBirth());

        Profile profile = profileRepository.findByUserId(account.getId())
                .orElseGet(() -> Profile.builder().user(account).build());

        // Date of birth is what the reviewer checks the ID against, so it cannot
        // be edited once a decision has been made on this account.
        boolean locked = account.getVerificationStatus() == VerificationStatus.APPROVED
                || account.getVerificationStatus() == VerificationStatus.PENDING_REVIEW;
        if (locked && profile.getDateOfBirth() != null
                && !profile.getDateOfBirth().equals(request.dateOfBirth())) {
            throw ApiException.conflict("dob_locked",
                    "Date of birth cannot be changed while verification is pending or approved. Contact support.");
        }

        profile.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                ? null : request.displayName().trim());
        profile.setBio(request.bio());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setGender(request.gender());
        profile.setCity(request.city());
        profile.setCountry(request.country());
        if (request.vibe() != null) {
            profile.setVibe(request.vibe());
        }
        if (request.discoverable() != null) {
            profile.setDiscoverable(request.discoverable());
        }
        return ProfileResponse.of(profileRepository.save(profile));
    }

    /**
     * Another member's profile.
     *
     * <p>Public: anyone may read it, so people can judge whether the app is worth
     * joining. Only approved, discoverable members appear, and the public view
     * withholds the private nickname and the exact date of birth.
     *
     * @param viewer the caller, or null when anonymous
     */
    @Transactional(readOnly = true)
    public ProfileResponse getPublic(UUID targetUserId, User viewer) {
        Profile profile = requireProfile(targetUserId);
        boolean self = viewer != null && profile.getUser().getId().equals(viewer.getId());

        if (self || (viewer != null && viewer.isStaff())) {
            return ProfileResponse.of(profile);
        }
        if (!profile.getUser().isApproved() || !profile.isDiscoverable()) {
            throw ApiException.notFound("Profile");
        }
        return ProfileResponse.publicView(profile);
    }

    @Transactional(readOnly = true)
    public Profile requireProfile(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Profile"));
    }

    /** Used by the KYC flow, which refuses to accept a submission without a profile. */
    @Transactional(readOnly = true)
    public boolean hasProfile(UUID userId) {
        return profileRepository.existsByUserId(userId);
    }

    /**
     * Promotes a viewer the first time they act like a creator.
     *
     * <p>There is no decision here the caller has not already made: nobody fills in
     * a public profile or uploads an identity document by accident. Keeping the
     * upgrade implicit removes a round trip and a whole class of client bug.
     */
    @Transactional
    public User upgradeToCreatorIfNeeded(User user) {
        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> ApiException.notFound("Account"));
        if (!managed.isCreator()) {
            managed.setAccountType(AccountType.CREATOR);
            log.info("Account {} became a creator by starting creator onboarding", managed.getId());
        }
        return managed;
    }

    private void requireAdult(LocalDate dateOfBirth) {
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        if (age < appProperties.minimumAge()) {
            throw ApiException.badRequest("underage",
                    "You must be at least " + appProperties.minimumAge() + " to use Nightgals");
        }
        if (age > 120) {
            throw ApiException.badRequest("invalid_dob", "Date of birth is not plausible");
        }
    }
}
