package com.nightgals.user;

import com.nightgals.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Assigning, suggesting and changing public handles. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsernameService {

    /** Letters, digits and underscores; must start with a letter. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,29}$");

    private static final int MAX_ATTEMPTS = 12;

    private final UserRepository userRepository;
    private final UsernameGenerator generator;

    @Value("${nightgals.username.change-cooldown:P30D}")
    private Duration changeCooldown;

    /**
     * A handle nobody currently holds. Used at registration, so it must not fail
     * - after the random attempts are exhausted it falls back to a counter-based
     * name that is guaranteed free.
     */
    @Transactional(readOnly = true)
    public String generateUnique() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generator.generate();
            if (!userRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        String fallback;
        long suffix = userRepository.count();
        do {
            fallback = "Member" + (++suffix);
        } while (userRepository.existsByUsernameIgnoreCase(fallback));
        log.warn("Username generator exhausted {} attempts, fell back to {}", MAX_ATTEMPTS, fallback);
        return fallback;
    }

    /** Fresh options for a "roll again" button, all confirmed free at this moment. */
    @Transactional(readOnly = true)
    public List<String> suggest(int count) {
        int wanted = Math.clamp(count, 1, 10);
        List<String> suggestions = new ArrayList<>(wanted);
        for (int attempt = 0; attempt < wanted * MAX_ATTEMPTS && suggestions.size() < wanted; attempt++) {
            String candidate = generator.generate();
            if (!suggestions.contains(candidate) && !userRepository.existsByUsernameIgnoreCase(candidate)) {
                suggestions.add(candidate);
            }
        }
        return suggestions;
    }

    @Transactional
    public String change(User user, String requested) {
        String username = requested.trim();

        if (!VALID.matcher(username).matches()) {
            throw ApiException.badRequest("invalid_username",
                    "Usernames are 3-30 characters, start with a letter, and use only letters, digits and underscores");
        }
        if (UsernameGenerator.isReserved(username)) {
            throw ApiException.badRequest("reserved_username", "That username is not available");
        }

        User managed = userRepository.findById(user.getId()).orElseThrow();

        if (username.equalsIgnoreCase(managed.getUsername())) {
            // Same handle in different case: allowed, and not a "change".
            managed.setUsername(username);
            return username;
        }

        Instant lastChanged = managed.getUsernameChangedAt();
        if (lastChanged != null && lastChanged.plus(changeCooldown).isAfter(Instant.now())) {
            throw ApiException.conflict("change_too_soon",
                    "You can change your username again after "
                            + lastChanged.plus(changeCooldown).toString());
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("username_taken", "That username is already taken");
        }

        managed.setUsername(username);
        managed.setUsernameChangedAt(Instant.now());
        log.info("User {} changed username", managed.getId());
        return username;
    }

    /** Replaces the current handle with a fresh random one. Subject to the same cooldown. */
    @Transactional
    public String reroll(User user) {
        return change(user, generateUnique());
    }
}
