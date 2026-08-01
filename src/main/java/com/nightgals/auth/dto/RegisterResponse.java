package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        A new account, already signed in.

        Registration does not wait on email. `auth` holds working tokens, so a
        viewer can start browsing the moment they submit the form, and
        `emailVerification` is the challenge for confirming their address
        whenever they get round to it.
        """)
public record RegisterResponse(

        @Schema(description = "Tokens and identity - the account is usable straight away")
        AuthResponse auth,

        @Schema(description = """
                Challenge for confirming the email address, to answer at
                `/auth/email/verify`. Null if the confirmation email could not be
                sent - the account still works, it can be re-requested later.
                """)
        OtpChallengeResponse emailVerification) {
}
