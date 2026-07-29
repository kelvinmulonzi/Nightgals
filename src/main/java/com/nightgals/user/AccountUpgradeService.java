package com.nightgals.user;

import com.nightgals.common.ApiException;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.user.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a viewer to a creator account.
 *
 * <p>One direction only. Going back would leave published content and an earnings
 * ledger belonging to an account that is no longer a creator, so a creator who
 * wants to stop posting deletes their media instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountUpgradeService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    @Transactional
    public MeResponse becomeCreator(User user) {
        User managed = userRepository.findById(user.getId())
                .orElseThrow(() -> ApiException.notFound("Account"));

        if (managed.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("account_not_active",
                    "This account cannot become a creator while it is " + managed.getStatus());
        }

        if (!managed.isCreator()) {
            managed.setAccountType(AccountType.CREATOR);
            log.info("Account {} upgraded to CREATOR", managed.getId());
        }

        return MeResponse.of(managed, profileRepository.existsByUserId(managed.getId()));
    }
}
