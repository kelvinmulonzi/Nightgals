package com.nightgals.auth.otp;

import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.OtpProperties;
import com.nightgals.mail.EmailService;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Issuing, resending and consuming one-time codes.
 *
 * <p>Three things keep a six-digit code honest, and all of them live here:
 * a short life, a hard cap on wrong guesses, and a cap on how many challenges an
 * account may open per hour. Without the third, an attacker who knows a password
 * could simply keep opening challenges until one code happened to be guessable.
 *
 * <p>Codes are compared by hash, in constant time. The plaintext exists only
 * between {@link #generateCode()} and the SMTP handoff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challengeRepository;
    private final OtpProperties properties;
    private final EmailService emailService;

    /**
     * What the client is given after a challenge is opened. Deliberately carries
     * no code and no email address - only a masked hint of which inbox to check.
     */
    public record Challenge(UUID challengeId, Instant expiresAt, String maskedEmail, int codeLength) {
    }

    /**
     * Opens a challenge and emails the code.
     *
     * <p>Any earlier challenge of the same purpose is retired first, so exactly
     * one code is ever live per user per purpose.
     *
     * <p>The email is sent inside the transaction on purpose: if it cannot be
     * delivered the whole thing rolls back, rather than leaving a challenge the
     * user has no way to answer. That is the right trade for a sign-in, where a
     * code that never arrives is a dead end. Registration wants the opposite -
     * see {@link #issueQuietly}.
     */
    @Transactional
    public Challenge issue(User user, OtpPurpose purpose, String ipAddress) {
        return open(user, purpose, ipAddress, true);
    }

    /**
     * Opens a challenge, but treats an undeliverable email as "no challenge"
     * rather than as a failure.
     *
     * <p>For registration, where losing a signup to a mail outage is far worse
     * than an unconfirmed address. Returns {@code null} when the code could not
     * be sent, so the caller can tell the client there is nothing to answer.
     *
     * <p><b>The failure must be swallowed here, not by the caller.</b> This class
     * is {@code @Transactional} and joins whatever transaction the caller already
     * has; letting an exception escape this method's proxy marks that shared
     * transaction rollback-only, and no {@code catch} further up can undo it -
     * the caller returns happily and the commit then fails with
     * {@code UnexpectedRollbackException}.
     */
    @Transactional
    public Challenge issueQuietly(User user, OtpPurpose purpose, String ipAddress) {
        return open(user, purpose, ipAddress, false);
    }

    private Challenge open(User user, OtpPurpose purpose, String ipAddress, boolean deliveryRequired) {
        requireWithinRateLimit(user);
        retireOutstanding(user, purpose);

        String code = generateCode();
        OtpChallenge challenge = challengeRepository.save(OtpChallenge.builder()
                .user(user)
                .purpose(purpose)
                .codeHash(Hashing.sha256(code))
                .expiresAt(Instant.now().plus(properties.ttl()))
                .ipAddress(ipAddress)
                .build());

        try {
            deliver(user, purpose, code);
        } catch (RuntimeException e) {
            if (deliveryRequired) {
                throw e;
            }
            // Burn it rather than leave it outstanding: a challenge whose code
            // is sitting in a dead SMTP connection would otherwise put a code box
            // in front of somebody waiting for mail that is never coming, and
            // would block the next request through the one-live-code rule.
            challenge.setConsumedAt(Instant.now());
            log.warn("Could not deliver {} code to user {}: {}", purpose, user.getId(), e.getMessage());
            return null;
        }

        log.info("Issued {} challenge {} for user {}", purpose, challenge.getId(), user.getId());
        return describe(challenge, user);
    }

    /**
     * Sends a fresh code for an existing challenge.
     *
     * <p>A new code replaces the old one - the previous code stops working the
     * moment this returns, so a resend cannot widen the guessing window.
     */
    @Transactional
    public Challenge resend(UUID challengeId) {
        OtpChallenge challenge = challengeRepository.findWithUser(challengeId)
                .orElseThrow(OtpService::invalidChallenge);

        if (!challenge.isUsable()) {
            throw invalidChallenge();
        }
        if (challenge.getResends() >= properties.maxResends()) {
            throw ApiException.conflict("resend_limit",
                    "Too many codes requested. Start again to get a new one.");
        }
        Instant lastSent = challenge.getUpdatedAt() == null
                ? challenge.getCreatedAt() : challenge.getUpdatedAt();
        if (lastSent != null && lastSent.plus(properties.resendCooldown()).isAfter(Instant.now())) {
            throw ApiException.conflict("resend_too_soon",
                    "Give the last code a moment to arrive before asking for another.");
        }

        String code = generateCode();
        challenge.setCodeHash(Hashing.sha256(code));
        challenge.setResends(challenge.getResends() + 1);
        // Resending restarts the clock; the old expiry would often be seconds away.
        challenge.setExpiresAt(Instant.now().plus(properties.ttl()));
        // A fresh code deserves a fresh allowance of guesses.
        challenge.setAttempts(0);

        deliver(challenge.getUser(), challenge.getPurpose(), code);

        return describe(challenge, challenge.getUser());
    }

    /**
     * Checks a code and burns the challenge.
     *
     * <p>Returns the account the challenge belongs to. The caller decides what
     * that means - issuing tokens, or marking an address confirmed.
     *
     * @throws ApiException 401 for anything wrong: unknown challenge, expired,
     *                      already used, wrong purpose, or a bad code
     */
    @Transactional
    public User consume(UUID challengeId, String code, OtpPurpose expectedPurpose) {
        OtpChallenge challenge = challengeRepository.findWithUser(challengeId)
                .orElseThrow(OtpService::invalidChallenge);

        if (challenge.getPurpose() != expectedPurpose) {
            throw invalidChallenge();
        }
        if (challenge.getConsumedAt() != null) {
            throw ApiException.unauthorized("That code has already been used. Request a new one.");
        }
        if (challenge.isExpired()) {
            throw ApiException.unauthorized("That code has expired. Request a new one.");
        }
        if (challenge.getAttempts() >= properties.maxAttempts()) {
            throw ApiException.unauthorized("Too many wrong codes. Start again.");
        }

        String submitted = code == null ? "" : code.trim().replaceAll("\\s+", "");
        if (!matches(challenge.getCodeHash(), submitted)) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            int left = properties.maxAttempts() - challenge.getAttempts();
            if (left <= 0) {
                // Burn it rather than leaving a dead row a client might keep retrying.
                challenge.setConsumedAt(Instant.now());
                throw ApiException.unauthorized("Too many wrong codes. Start again.");
            }
            throw ApiException.unauthorized(
                    "That code is not right. " + left + (left == 1 ? " try" : " tries") + " left.");
        }

        challenge.setConsumedAt(Instant.now());
        log.info("Consumed {} challenge {}", challenge.getPurpose(), challengeId);
        return challenge.getUser();
    }

    /** Removes rows that expired more than a day ago. */
    @Transactional
    public int purgeExpired() {
        int removed = challengeRepository.deleteExpiredBefore(
                Instant.now().minus(java.time.Duration.ofDays(1)));
        if (removed > 0) {
            log.debug("Purged {} expired OTP challenges", removed);
        }
        return removed;
    }

    /**
     * Whether this sign-in needs a code.
     *
     * <p>The code proves that whoever has the password also reads the inbox on the
     * account. That proof does not decay, so by default it is asked for once and
     * recorded as {@code email_verified} - at first sign-in for most people, or at
     * registration for anyone who answered the confirmation code there.
     *
     * <p>Set {@code nightgals.otp.login-first-time-only} to false to ask every
     * time, or {@code login-required} to false to stop asking at all.
     */
    public boolean loginCodeRequiredFor(User user) {
        if (!properties.loginRequired()) {
            return false;
        }
        return !properties.loginFirstTimeOnly() || !user.isEmailVerified();
    }

    /**
     * A challenge-shaped response that answers to nothing.
     *
     * <p>For "forgot my password" against an address that has no account. Returning
     * nothing, or a different status, would turn the endpoint into a way of asking
     * whether somebody is a member here - which on this platform is not a harmless
     * thing to be able to ask. So the caller gets the same screen either way, and
     * the code they are waiting for simply never arrives.
     *
     * <p>Nothing is written and no mail is sent: the id is random and will not be
     * found when it comes back.
     */
    public Challenge decoy(String email) {
        return new Challenge(UUID.randomUUID(), Instant.now().plus(properties.ttl()),
                maskEmail(email), properties.length());
    }

    public String maskEmail(String email) {
        return com.nightgals.mail.EmailAddresses.mask(email);
    }

    // ------------------------------------------------------------ internals

    /**
     * Burns any code of this kind that is still outstanding, so exactly one is
     * ever live per user per purpose.
     *
     * <p>Without this, somebody who clicks sign-in three times ends up holding
     * three working codes and no idea which the app expects.
     */
    private void retireOutstanding(User user, OtpPurpose purpose) {
        Instant now = Instant.now();
        for (OtpChallenge stale : challengeRepository.findOutstanding(user.getId(), purpose)) {
            stale.setConsumedAt(now);
        }
    }

    private void deliver(User user, OtpPurpose purpose, String code) {
        switch (purpose) {
            case LOGIN -> emailService.sendLoginCode(
                    user.getEmail(), user.getUsername(), code, properties.ttl());
            case EMAIL_VERIFICATION -> emailService.sendVerificationCode(
                    user.getEmail(), user.getUsername(), code, properties.ttl());
            case PASSWORD_RESET -> emailService.sendPasswordResetCode(
                    user.getEmail(), user.getUsername(), code, properties.ttl());
        }
    }

    private Challenge describe(OtpChallenge challenge, User user) {
        return new Challenge(
                challenge.getId(),
                challenge.getExpiresAt(),
                maskEmail(user.getEmail()),
                properties.length());
    }

    private void requireWithinRateLimit(User user) {
        long recent = challengeRepository.countRecent(
                user.getId(), Instant.now().minus(java.time.Duration.ofHours(1)));
        if (recent >= properties.maxChallengesPerHour()) {
            throw ApiException.conflict("otp_rate_limited",
                    "Too many codes requested for this account. Try again in an hour.");
        }
    }

    /** Zero-padded, so a code beginning with 0 is still the configured length. */
    private String generateCode() {
        int bound = (int) Math.pow(10, properties.length());
        return String.format(Locale.ROOT, "%0" + properties.length() + "d", RANDOM.nextInt(bound));
    }

    /** Constant-time, so response timing does not leak how much of a code was right. */
    private boolean matches(String expectedHash, String submittedCode) {
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                Hashing.sha256(submittedCode).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One message for every way a challenge can be unusable. Distinguishing them
     * would tell an attacker holding a random UUID whether it exists.
     */
    private static ApiException invalidChallenge() {
        return ApiException.unauthorized("That sign-in attempt is no longer valid. Start again.");
    }
}
