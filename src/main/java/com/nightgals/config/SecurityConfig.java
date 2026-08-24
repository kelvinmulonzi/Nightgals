package com.nightgals.config;

import com.nightgals.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            // Answering a challenge is how a session is obtained, so it cannot
            // itself require one.
            // Google sign-in is a way of obtaining a session, so like the rest
            // of these it cannot require one. The token in the body is the
            // credential, and GoogleTokenVerifier checks it.
            "/api/v1/auth/oauth/google",
            "/api/v1/auth/otp/verify",
            "/api/v1/auth/otp/resend",
            "/api/v1/auth/email/verify",
            // Recovery cannot require a session: somebody who has forgotten their
            // password has no way to get one. The code emailed to the address on
            // the account is what stands in for the credential here.
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // The city picker, needed by the signup flow before there is a session.
            "/api/v1/cities",
            // Deployment settings the sign-up screen needs before there is a
            // session to read /me with. Configuration only - no account data.
            "/api/v1/config",
            "/api/v1/usernames/suggestions",
            "/api/v1/billing/plans",
            // MTN has no credential of ours to present, so this cannot be
            // authenticated. It is safe only because the handler treats the body
            // as a rumour and re-reads the real status from MTN before settling
            // anything - see MomoCallbackController.
            "/api/v1/webhooks/momo",
            // Stripe has no credential of ours either, but unlike MTN it signs
            // every delivery: the handler verifies that signature against the
            // endpoint's secret and rejects anything that fails. Permitting the
            // path is not the same as trusting the body - see
            // StripeWebhookController.
            "/api/v1/webhooks/stripe",
            // The payment picker, so a checkout screen can render its options
            // before sign-in. Reads configuration, exposes no account data.
            "/api/v1/billing/payment-methods",
            // The gift catalogue, for the same reason: somebody deciding whether
            // to sign up should be able to see what sending one costs. Reads
            // configuration only - sending one is authenticated, below.
            "/api/v1/live/gifts",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AppProperties appProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless bearer-token API: there is no session cookie for CSRF to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Public shop window: browse members, read a profile, see the
                        // gallery (locked past the free preview) and who is live. Reads
                        // only - and never the paid content itself.
                        // The creator price list, so somebody deciding whether to
                        // sign up can see what publishing costs first. GET only -
                        // the POST on the same path is the buy endpoint.
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing/creator-packages").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members").permitAll()
                        // The city shortcuts beside the filters. Counts only, over the
                        // same population the public feed already shows.
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/cities").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/*/profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/*/media").permitAll()
                        // What the whole gallery costs. A price is the shop window's
                        // job, and a visitor weighing up an account should see it
                        // before signing up rather than after. GET only - the POST on
                        // the same path spends money and stays authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing/members/*/unlock-all").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/live").permitAll()
                        // The calendar is a shop window too: somebody should be able
                        // to see what is on before deciding to sign up for it.
                        .requestMatchers(HttpMethod.GET, "/api/v1/live/upcoming").permitAll()
                        // Open, but not unguarded. Every broadcast is paid to join now,
                        // so nothing here gives a stranger a stream: what it gives them
                        // is LiveSessionService's own 401, which says to create an
                        // account. Behind the filter the answer would come from Spring
                        // instead - "send Authorization: Bearer <token>" - which is a
                        // message for whoever wrote the client, shown to somebody who
                        // just wanted to watch.
                        .requestMatchers(HttpMethod.GET, "/api/v1/live/*/playback").permitAll()
                        // /watch is the same door, for a provider that hands out a token
                        // rather than a URL, so it is open on the same terms and refused
                        // by the same entitlement check. Publishing credentials live
                        // under /me and stay authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/v1/live/*/watch").permitAll()
                        // The gift feed on that broadcast. Read-only, and it shows public
                        // handles and amounts - the same things the room shows. Sending
                        // one is a POST and stays authenticated: it spends a balance.
                        .requestMatchers(HttpMethod.GET, "/api/v1/live/*/gifts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/*/call-rates").permitAll()
                        // Without this the preview URLs above would be dead links.
                        // MediaService still refuses anything past the free preview.
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/*/file").permitAll()
                        // Reels are the shop window: promotional, free, and pointless
                        // behind a login. Listed before the /admin/** rule below, which
                        // still guards posting and removing them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/reels").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reels/*/file").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("MODERATOR", "ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, 401, "unauthorized",
                                        "Authentication required. Send Authorization: Bearer <token>."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "forbidden",
                                        "You do not have permission to perform this action.")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 12: deliberately slow, since this is the last line of defence on a
        // database holding identity-verified accounts.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(appProperties.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Hand-rolled so security failures use the same envelope as ErrorResponse. */
    private static void writeError(jakarta.servlet.http.HttpServletResponse response,
                                   int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"code":"%s","message":"%s"}"""
                .formatted(java.time.Instant.now(), status, code, message));
    }

}
