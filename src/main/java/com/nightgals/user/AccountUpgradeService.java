package com.nightgals.user;

import com.nightgals.common.ApiException;
import com.nightgals.earnings.EarningRepository;
import com.nightgals.media.MediaRepository;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.user.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving an account between viewer and creator.
 *
 * <p>Both directions change one column and nothing else. There is never a second
 * account and never a second email address: the handle, the unlocks, the payment
 * history and the referral code all belong to the person, not to what they
 * happened to pick on the signup form. Somebody who joined to watch and later
 * wants to post is the same customer, and making them register again would cost
 * them everything they had bought.
 *
 * <p><b>Upgrading is unconditional; downgrading is not.</b> Going back is refused
 * while the account still has something that only a creator account can own -
 * published media, an identity check, or money we have not paid out yet. Those
 * would otherwise be left belonging to an account that is no longer a creator:
 * a gallery nobody can moderate, a KYC record against a viewer, a balance with
 * no payout screen to reach it from. The refusal says which one it is, so the
 * holder can clear it and come back rather than guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountUpgradeService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final MediaRepository mediaRepository;
    private final EarningRepository earningRepository;

    @Transactional
    public MeResponse becomeCreator(User user) {
        User managed = active(user, "become a creator");

        if (!managed.isCreator()) {
            managed.setAccountType(AccountType.CREATOR);
            log.info("Account {} upgraded to CREATOR", managed.getId());
        }
        return me(managed);
    }

    /**
     * Back to a viewer account, when there is nothing left that would be orphaned.
     *
     * <p>A no-op for an account that is already a viewer, so a client that is not
     * sure which way round things are can just call it.
     */
    @Transactional
    public MeResponse becomeViewer(User user) {
        User managed = active(user, "go back to a viewer account");

        if (!managed.isCreator()) {
            return me(managed);
        }
        requireNothingOrphaned(managed);

        managed.setAccountType(AccountType.VIEWER);
        // The verification verdict is left exactly as it is. It is a fact about a
        // document somebody showed us, not a permission, and erasing it would
        // mean asking for their passport a second time if they ever came back.
        log.info("Account {} moved back to VIEWER", managed.getId());
        return me(managed);
    }

    // ------------------------------------------------------------ internals

    /**
     * Each check names the one thing standing in the way, and names what to do
     * about it. "You cannot do that" is not an error message somebody can act on.
     */
    private void requireNothingOrphaned(User user) {
        long posted = mediaRepository.countByUserId(user.getId());
        if (posted > 0) {
            throw ApiException.conflict("has_media",
                    "This account still has " + posted + " item" + (posted == 1 ? "" : "s")
                            + " posted. Delete them first, then switch back.");
        }

        long unpaid = earningRepository.sumUnpaidNet(user.getId());
        if (unpaid > 0) {
            throw ApiException.conflict("has_earnings",
                    "This account still has earnings that have not been paid out. "
                            + "Withdraw them first, then switch back.");
        }

        // Checked last because it is the one somebody cannot undo themselves - a
        // submitted identity check has to be resolved by a moderator, so there is
        // no point raising it while the other two are still fixable.
        if (user.getVerificationStatus() == VerificationStatus.PENDING_REVIEW) {
            throw ApiException.conflict("kyc_in_review",
                    "An identity check is being reviewed on this account. "
                            + "Wait for the decision, then switch back.");
        }
    }

    private User active(User user, String what) {
        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> ApiException.notFound("Account"));

        if (managed.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("account_not_active",
                    "This account cannot " + what + " while it is " + managed.getStatus());
        }
        return managed;
    }

    private MeResponse me(User user) {
        return MeResponse.of(user, profileRepository.existsByUserId(user.getId()));
    }
}
