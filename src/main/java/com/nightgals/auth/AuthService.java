package com.nightgals.auth;

import com.nightgals.auth.dto.AuthResponse;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.JwtProperties;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UsernameService;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UsernameService usernameService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }

        User user = userRepository.save(User.builder()
                .email(email)
                .accountType(request.accountType() == null ? AccountType.VIEWER : request.accountType())
                // Every account gets a pseudonym up front, so nobody is ever
                // identified to other members by their real name.
                .username(usernameService.generateUnique())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .verificationStatus(VerificationStatus.UNVERIFIED)
                .build());

        log.info("Registered {} account {}", user.getAccountType(), user.getId());
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                // Same message whether the email is unknown or the password is
                // wrong, so the endpoint cannot be used to enumerate accounts.
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("account_suspended",
                    "This account has been suspended. Contact support.");
        }
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            throw ApiException.forbidden("account_deactivated", "This account has been closed");
        }

        user.setLastLoginAt(Instant.now());
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(Hashing.sha256(request.refreshToken()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (!stored.isUsable()) {
            throw ApiException.unauthorized("Refresh token has expired or been revoked");
        }

        // Rotate: the presented token dies with the response that replaces it,
        // so a stolen token is usable at most once.
        stored.setRevokedAt(Instant.now());
        return issueTokens(stored.getUser());
    }

    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(Hashing.sha256(request.refreshToken()))
                .filter(RefreshToken::isUsable)
                .ifPresent(token -> token.setRevokedAt(Instant.now()));
    }

    @Transactional
    public void logoutEverywhere(User user) {
        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        log.info("Revoked {} refresh tokens for user {}", revoked, user.getId());
    }

    private AuthResponse issueTokens(User user) {
        String refreshToken = generateOpaqueToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(Hashing.sha256(refreshToken))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()))
                .build());

        return new AuthResponse(
                jwtService.issueAccessToken(user),
                refreshToken,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                user.getId(),
                user.getUsername(),
                user.getAccountType(),
                user.getRole(),
                user.getVerificationStatus(),
                profileRepository.existsByUserId(user.getId()));
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
