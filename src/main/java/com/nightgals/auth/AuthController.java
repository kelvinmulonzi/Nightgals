package com.nightgals.auth;

import com.nightgals.auth.dto.AuthResponse;
import com.nightgals.auth.dto.ForgotPasswordRequest;
import com.nightgals.auth.dto.GoogleLoginRequest;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.LoginResponse;
import com.nightgals.auth.dto.OtpChallengeResponse;
import com.nightgals.auth.dto.OtpVerifyRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.dto.RegisterResponse;
import com.nightgals.auth.dto.ResendRequest;
import com.nightgals.auth.dto.ResetPasswordRequest;
import com.nightgals.common.ErrorResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. Authentication", description = """
        Register, sign in, and manage sessions.

        **Signing in takes two calls.** `POST /login` checks the password and emails a
        six-digit code; `POST /otp/verify` exchanges that code for tokens. A password on
        its own is never enough, so a leaked or reused one does not cost anybody their
        account.

        **Registering takes one.** The account is created and signed in immediately - the
        confirmation code is sent alongside, but nothing waits on it.
        """)
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "Register a new account",
            description = """
                    Creates the account and signs the user straight in. The response carries
                    working tokens, so the app is usable before the confirmation email lands.

                    **`accountType` decides everything that follows.** It defaults to `VIEWER`.

                    **Viewer** - somebody who wants to watch. Nothing further is asked of them:
                    no profile, no date of birth, no identity documents. They browse, pay for
                    whichever creator they want to see, and watch. `nextStep` comes back as
                    `BROWSE`.

                    **Creator** - somebody who wants to post and earn. The path is:
                    1. `PUT /api/v1/me/profile` - display details and date of birth
                    2. `POST /api/v1/me/kyc` - submit an ID or passport
                    3. Wait for an administrator to approve
                    4. `POST /api/v1/billing/creator-packages` - buy BRONZE, SILVER or GOLD
                    5. `POST /api/v1/me/media/photos` - unlocked by the package

                    A viewer who later wants to post calls `POST /api/v1/me/become-creator`;
                    they keep their handle, their purchases and their history.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "201", description = "Account created and signed in")
    @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request, clientIp(http)));
    }

    @Operation(
            summary = "Step 1 of signing in: check the password, send a code",
            description = """
                    A correct password does not return tokens. It returns a challenge, and a
                    six-digit code goes to the address on the account.

                    Read `otpRequired` to decide what to do with the response:

                    * `true` - show a code box, then send the code and `challengeId` to
                      `POST /auth/otp/verify`. `maskedEmail` tells the user which inbox to open.
                    * `false` - codes are switched off in this environment and `auth` already
                      holds the tokens. Nothing else to do.

                    A wrong email and a wrong password give the same `401`, so this cannot be
                    used to find out who has an account.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Code sent, or signed in when codes are off")
    @ApiResponse(responseCode = "401", description = "Invalid email or password",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Account suspended or closed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Too many codes requested for this account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "The code could not be emailed - retry",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, clientIp(http));
    }

    @Operation(
            summary = "Step 2 of signing in: exchange the emailed code for tokens",
            description = """
                    Codes are single-use and short-lived, and a challenge is burned after a few
                    wrong guesses - the error says how many are left.

                    Succeeding here also marks the address confirmed, since reading the code
                    proves control of the inbox.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Wrong, expired, or already-used code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return authService.verifyLoginCode(request);
    }

    @Operation(
            summary = "Sign in with Google",
            description = """
                    One call, no code. Send the `credential` the browser got back from
                    Google and this returns tokens.

                    The confirmation code exists to prove somebody reads the inbox on
                    the account. A Google token minted for this application already
                    proves that, so there is nothing left for a code to establish.

                    **This is the viewers' door.** A first sign-in creates a `VIEWER`
                    account - never a creator, because becoming one is a deliberate
                    step with a profile and identity documents behind it, and it is
                    taken later through `POST /api/v1/me/become-creator`.

                    A creator who has a password gets a `403` and is sent back to
                    `POST /auth/login`: their account holds identity documents and a
                    payout balance, and its two-step sign-in is not something a Google
                    token should be able to shorten. The one exception is a creator who
                    has never had a password - somebody who joined through Google and
                    upgraded - for whom this is the only door there has ever been.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Signed in, account created if it was new")
    @ApiResponse(responseCode = "401", description = "The token is not a valid, current Google token for this app",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Creator account, or account suspended or closed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Google sign-in is not configured in this environment",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/oauth/google")
    public AuthResponse googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request);
    }

    @Operation(
            summary = "Send a fresh code",
            description = """
                    Replaces the code on an outstanding challenge - the previous one stops
                    working immediately, and both the expiry and the remaining guesses reset.

                    Rate limited: a short cooldown between sends and a cap per challenge, so
                    this cannot be pointed at somebody's inbox.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "A new code is on its way")
    @ApiResponse(responseCode = "401", description = "That challenge is no longer valid",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Asked too soon, or too many times",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/otp/resend")
    public OtpChallengeResponse resendOtp(@Valid @RequestBody ResendRequest request) {
        return authService.resendCode(request.challengeId());
    }

    @Operation(
            summary = "Confirm the email address on a new account",
            description = """
                    Answers the `emailVerification` challenge from `POST /auth/register`.

                    Optional, and nothing is gated on it - a viewer can browse and pay without
                    ever confirming. It is what makes account recovery possible, and it happens
                    by itself the first time somebody signs in with a code.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "204", description = "Address confirmed")
    @ApiResponse(responseCode = "401", description = "Wrong, expired, or already-used code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody OtpVerifyRequest request) {
        authService.verifyEmail(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Ask for a new confirmation code",
            description = "For a signed-in user whose address is still unconfirmed.")
    @ApiResponse(responseCode = "200", description = "A code is on its way")
    @ApiResponse(responseCode = "409", description = "Already confirmed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/email/request-code")
    public OtpChallengeResponse requestEmailCode(@AuthenticationPrincipal AuthUser principal,
                                                 HttpServletRequest http) {
        return authService.requestEmailVerification(principal.user(), clientIp(http));
    }

    @Operation(
            summary = "Step 1 of a forgotten password: email a recovery code",
            description = """
                    Always returns a challenge, whether or not that address has an account.

                    An address nobody has registered gets a challenge id that answers to
                    nothing and no email; a suspended or closed account is treated the same
                    way. That is deliberate - a response that differed would turn this into a
                    way of asking whether a given person is a member of this site, which is
                    not a harmless thing to be able to ask here.

                    So the client shows the code screen either way, and somebody who mistyped
                    their address finds out from the code that never arrives.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "A code is on its way, if that address has an account")
    @ApiResponse(responseCode = "409", description = "Too many codes requested for this account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/password/forgot")
    public OtpChallengeResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest http) {
        return authService.requestPasswordReset(request, clientIp(http));
    }

    @Operation(
            summary = "Step 2 of a forgotten password: set a new one and sign in",
            description = """
                    The code is the whole check here - there is no old password to ask for, by
                    definition - so it gets the same treatment as any other: single use, short
                    lived, and burned after a few wrong guesses. `POST /auth/otp/resend` sends
                    a fresh one for the same challenge.

                    The new password must satisfy the same rules as registration.

                    **Every existing session is revoked.** Recovery exists for accounts that
                    may already be in somebody else's hands, so anything already signed in is
                    signed out; the tokens in the response are the only ones left working.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Password changed and signed in")
    @ApiResponse(responseCode = "401", description = "Wrong, expired, or already-used code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Account suspended or closed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @Operation(
            summary = "Exchange a refresh token for a new access token",
            description = "Refresh tokens rotate: the token you send is revoked and a new one returned.",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "New tokens issued")
    @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired or already used",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Sign out of this device", security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "204", description = "Refresh token revoked")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Sign out of every device",
            description = "Revokes all refresh tokens for the caller.")
    @ApiResponse(responseCode = "204", description = "All sessions revoked")
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere(@AuthenticationPrincipal AuthUser principal) {
        authService.logoutEverywhere(principal.user());
        return ResponseEntity.noContent().build();
    }

    /**
     * Best-effort client address, recorded on challenges for abuse investigation.
     *
     * <p>{@code X-Forwarded-For} is trusted only as far as this is deployed behind
     * a proxy that sets it. Nothing is authorised on the result, so a spoofed value
     * costs nothing.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String candidate = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        if (candidate == null) {
            return null;
        }
        return candidate.length() > 45 ? candidate.substring(0, 45) : candidate;
    }
}
