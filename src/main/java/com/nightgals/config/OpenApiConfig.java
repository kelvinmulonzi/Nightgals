package com.nightgals.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    /** Referenced by @SecurityRequirement on protected controllers. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI nightgalsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nightgals API")
                        .version("v1")
                        .description("""
                                Nightgals is a verified-members-only social app for finding people to go out with.

                                ## How verification gates the API

                                Every account moves through these states, tracked as `verificationStatus`:

                                | State | Meaning | What the user can do |
                                |---|---|---|
                                | `UNVERIFIED` | Registered, no ID submitted | Manage their own profile only |
                                | `PENDING_REVIEW` | ID submitted, awaiting a human | Read-only; may withdraw the submission |
                                | `APPROVED` | An admin matched their ID to their selfie | Upload photos and video, be discoverable |
                                | `REJECTED` | Review failed | See the reason, submit again |

                                Endpoints marked **Requires: APPROVED** return `403 verification_required`
                                until an administrator approves the account.

                                ## Authentication

                                Call `POST /api/v1/auth/login`, then send `Authorization: Bearer <accessToken>`
                                on every other call. Access tokens are short-lived - use
                                `POST /api/v1/auth/refresh` to get a new one.

                                ## Handling of identity documents

                                Uploaded IDs and passports are private. They are readable only by
                                `MODERATOR` and `ADMIN` roles, every read is written to an audit log,
                                and the raw document number is never persisted - only a salted hash
                                (used to detect duplicate accounts) and the last four characters.
                                """)
                        .contact(new Contact().name("Nightgals Engineering"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
