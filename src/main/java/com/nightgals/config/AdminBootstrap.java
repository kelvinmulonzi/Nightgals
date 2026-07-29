package com.nightgals.config;

import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator on an empty database.
 *
 * <p>Without this there is no way to approve anybody: approving requires a
 * MODERATOR or ADMIN, and nothing in the API grants those roles to a fresh
 * account. Runs once - if the account already exists it is left untouched, so
 * changing the password later is safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${nightgals.bootstrap.admin-email:admin@nightgals.local}")
    private String adminEmail;

    @Value("${nightgals.bootstrap.admin-password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
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

        userRepository.save(User.builder()
                .email(adminEmail.toLowerCase())
                .username("NightgalsTeam")
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .verificationStatus(VerificationStatus.APPROVED)
                .emailVerified(true)
                .build());

        log.info("Created bootstrap administrator {}", adminEmail);
    }
}
