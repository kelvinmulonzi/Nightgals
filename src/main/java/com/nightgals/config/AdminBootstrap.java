package com.nightgals.config;

import com.nightgals.referral.ReferralService;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
import com.nightgals.user.UsernameService;
import com.nightgals.user.VerificationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first administrator on an empty database.
 *
 * <p>Without this there is no way to approve anybody: approving requires a
 * MODERATOR or ADMIN, and nothing in the API grants those roles to a fresh
 * account. Runs once - if the account already exists it is left untouched, so
 * changing the password later is safe.
 *
 * <p>Two things here exist because this runner once took production down for
 * thirteen hours. It used to hard-code the handle {@code NightgalsTeam} while
 * deciding whether to run at all by <i>email</i>, so the day a real
 * administrator was given that handle every subsequent boot tried to insert a
 * duplicate, hit {@code ux_users_username}, and aborted startup. Hence: the
 * handle is checked for availability and given up rather than insisted upon,
 * and nothing that happens in here is allowed to prevent the application from
 * starting. Seeding a convenience account is never worth an outage - a missing
 * administrator can be created later, a server that will not boot cannot.
 *
 * <p>The transaction is managed explicitly rather than with
 * {@code @Transactional} for that second reason: on a self-invoked proxy the
 * commit happens after the method returns, so a {@code catch} inside it cannot
 * stop a constraint violation from escaping into {@code run}.
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    /** Used unless BOOTSTRAP_ADMIN_USERNAME says otherwise, and only if free. */
    private static final String DEFAULT_USERNAME = "NightgalsTeam";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ReferralService referralService;
    private final UsernameService usernameService;
    private final TransactionTemplate transactions;

    @Value("${nightgals.bootstrap.admin-email:admin@nightgals.local}")
    private String adminEmail;

    @Value("${nightgals.bootstrap.admin-password:}")
    private String adminPassword;

    @Value("${nightgals.bootstrap.admin-username:}")
    private String adminUsername;

    public AdminBootstrap(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          ReferralService referralService,
                          UsernameService usernameService,
                          PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.referralService = referralService;
        this.usernameService = usernameService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            transactions.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.error("""
                    No bootstrap administrator was created: {}
                    Startup continues anyway. If nobody can approve KYC submissions, \
                    create an administrator by hand.""", e.toString());
        }
    }

    private void seed() {
        if (userRepository.existsByEmailIgnoreCase(adminEmail)) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("""
                    No bootstrap admin created: nightgals.bootstrap.admin-password is not set.
                    Set BOOTSTRAP_ADMIN_PASSWORD and restart, or no one will be able to \
                    approve KYC submissions.""");
            return;
        }

        String username = resolveUsername();

        userRepository.save(User.builder()
                .email(adminEmail.toLowerCase())
                .username(username)
                // Every account carries one, including this one - the column is
                // NOT NULL and staff can invite people too.
                .referralCode(referralService.generateUniqueCode())
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .verificationStatus(VerificationStatus.APPROVED)
                .emailVerified(true)
                .build());

        log.info("Created bootstrap administrator {} as {}", adminEmail, username);
    }

    /**
     * The preferred handle if it is free, otherwise any handle at all.
     *
     * <p>A real member holding the preferred name is not an error worth failing
     * over: the administrator is identified by its email and its role, and the
     * public handle it happens to carry matters to nobody.
     */
    private String resolveUsername() {
        String preferred = adminUsername == null || adminUsername.isBlank()
                ? DEFAULT_USERNAME : adminUsername.trim();
        if (!userRepository.existsByUsernameIgnoreCase(preferred)) {
            return preferred;
        }
        String generated = usernameService.generateUnique();
        log.warn("Bootstrap username {} is already taken; the administrator will be {} instead",
                preferred, generated);
        return generated;
    }
}
