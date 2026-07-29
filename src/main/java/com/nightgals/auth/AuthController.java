package com.nightgals.auth;

import com.nightgals.auth.dto.AuthResponse;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ErrorResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. Authentication", description = "Register, sign in, and manage sessions")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "Register a new account",
            description = """
                    Creates the account and signs the user straight in.

                    **`accountType` decides everything that follows.** It defaults to `VIEWER`.

                    **Viewer** - somebody who wants to watch. Nothing further is asked of them:
                    no profile, no date of birth, no identity documents. They browse, unlock a
                    creator or subscribe, and watch. `nextStep` comes back as `BROWSE`.

                    **Creator** - somebody who wants to post and earn. The path is:
                    1. `PUT /api/v1/me/profile` - display details and date of birth
                    2. `POST /api/v1/me/kyc` - submit an ID or passport
                    3. Wait for an administrator to approve
                    4. `POST /api/v1/me/media/photos` - unlocked once approved

                    A viewer who later wants to post calls `POST /api/v1/me/become-creator`;
                    they keep their handle, their purchases and their history.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Sign in", security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Invalid email or password",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Account suspended or closed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
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
}
