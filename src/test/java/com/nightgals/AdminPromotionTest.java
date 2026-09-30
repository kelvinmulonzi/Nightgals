package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.config.AdminBootstrap;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured administrator is an administrator, even if they signed up first.
 *
 * <p>The bootstrap used to do nothing at all when an account already held the
 * configured address - it only ever created a missing one. So somebody who
 * registered through the front door before anyone set the variable stayed an
 * ordinary user forever, and the only way up was a hand-written UPDATE against
 * production.
 *
 * <p>Promotion only, and only for that one address: nothing here demotes anybody,
 * so it cannot strip the last administrator.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.bootstrap.admin-email=promote-me@example.com",
        // Blank, so the run at startup - before this test has created anybody -
        // has nothing to create and leaves the database alone.
        "nightgals.bootstrap.admin-password=",
})
class AdminPromotionTest {

    @Autowired AuthService authService;
    @Autowired AdminBootstrap adminBootstrap;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("An ordinary account holding the configured address is promoted")
    void existingAccountIsPromoted() {
        User before = register("promote-me@example.com");
        assertThat(before.getRole()).isEqualTo(Role.USER);

        adminBootstrap.run(null);

        assertThat(reload(before).getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("Promotion keeps the account otherwise untouched")
    void nothingElseChanges() {
        User before = register("promote-me@example.com");
        String username = before.getUsername();
        String hash = before.getPasswordHash();

        adminBootstrap.run(null);

        User after = reload(before);
        // Same person, same handle, same password - she is not asked to sign in
        // again, and her profile does not move.
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getUsername()).isEqualTo(username);
        assertThat(after.getPasswordHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("Everybody else is left alone")
    void othersAreUntouched() {
        User other = register("someone-else@example.com");

        adminBootstrap.run(null);

        assertThat(reload(other).getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("Running it twice changes nothing the second time")
    void idempotent() {
        User account = register("promote-me@example.com");

        adminBootstrap.run(null);
        adminBootstrap.run(null);

        // Still exactly one account on that address, still an administrator - the
        // second run must not have tried to insert a duplicate.
        assertThat(reload(account).getRole()).isEqualTo(Role.ADMIN);
        assertThat(userRepository.findByEmailIgnoreCase("promote-me@example.com")).isPresent();
    }

    /**
     * An ordinary account on that address.
     *
     * <p>Find-or-reset rather than create: this class cannot be transactional -
     * the bootstrap commits in a transaction of its own, and a test transaction
     * would hide the promotion from the assertion - so rows outlive each test and
     * a fixed address collides on the second one. Whatever is there is put back
     * to USER, which is the state every test here starts from.
     */
    private User register(String email) {
        var existing = userRepository.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User account = existing.get();
            account.setRole(Role.USER);
            return userRepository.saveAndFlush(account);
        }
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
