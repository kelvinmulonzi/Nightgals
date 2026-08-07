package com.nightgals;

import com.nightgals.billing.PaymentProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the Stripe method is wired up, reachable and refuses what it should.
 *
 * <p>No money and no network: a dummy secret key is enough to build the client,
 * because nothing here opens a Checkout Session. Taking a real payment cannot be
 * tested without real credentials, so what is covered is everything around it -
 * the method appearing in the picker, its alias resolving, and the webhook's
 * signature check, which is the whole security boundary and the one part that
 * would silently give content away if it were wrong.
 *
 * <p>The reconciliation sweep is pinned to a cron that will not fire during the
 * test, so it cannot reach out to Stripe mid-run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.providers=stripe,manual",
        "nightgals.stripe.secret-key=sk_test_dummy_key_for_wiring_only",
        "nightgals.stripe.publishable-key=pk_test_dummy",
        // A real-shaped secret that signs nothing, so every delivery fails
        // verification - which is exactly the case worth asserting.
        "nightgals.stripe.webhook-secret=whsec_dummy_secret_for_wiring_only",
        "nightgals.stripe.success-url=https://example.test/done?purchase={purchaseId}",
        "nightgals.stripe.cancel-url=https://example.test/cancelled?purchase={purchaseId}",
        "nightgals.stripe.reconcile-cron=0 0 5 29 2 ?",
})
class StripeWiringTest {

    @Autowired MockMvc mockMvc;
    @Autowired PaymentProviders paymentProviders;

    @Test
    @DisplayName("Card shows up in the picker, ahead of the methods listed after it")
    void cardIsOffered() throws Exception {
        mockMvc.perform(get("/api/v1/billing/payment-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("STRIPE"))
                .andExpect(jsonPath("$[0].label").value("Card"))
                // Cards need no phone number; the picker reads this rather than
                // hard-coding which methods want one.
                .andExpect(jsonPath("$[0].requiresPayerMsisdn").value(false))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                // Public by design, and served so that moving between test and
                // live keys is a server setting rather than an app release.
                .andExpect(jsonPath("$[0].clientKey").value("pk_test_dummy"));
    }

    @Test
    @DisplayName("The picker is readable before sign-in, so a checkout screen can render")
    void pickerIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/billing/payment-methods")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CARD resolves to Stripe, so clients need not name the processor")
    void cardAliasResolves() {
        assertThat(paymentProviders.resolve("CARD").name()).isEqualTo("STRIPE");
        assertThat(paymentProviders.resolve("card").name()).isEqualTo("STRIPE");
        assertThat(paymentProviders.resolve("STRIPE").name()).isEqualTo("STRIPE");
    }

    @Test
    @DisplayName("The secret key never leaves the server")
    void secretIsNotPublished() throws Exception {
        String body = mockMvc.perform(get("/api/v1/billing/payment-methods"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("sk_test");
        assertThat(body).contains("pk_test_dummy");
    }

    /** A body claiming a purchase was paid - the thing a forger would send. */
    private static final String PAID_EVENT = """
            {"id":"evt_1","type":"checkout.session.completed",
             "data":{"object":{"id":"cs_test_1",
             "client_reference_id":"00000000-0000-0000-0000-000000000001",
             "payment_status":"paid"}}}""";

    @Test
    @DisplayName("An unsigned webhook settles nothing, however valid the body looks")
    void unsignedWebhookIsRefused() throws Exception {
        // No Stripe-Signature header at all. This is the shape of the attack the
        // signature exists to stop: anyone who can POST claiming a purchase paid.
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAID_EVENT))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A forged signature is rejected rather than trusted")
    void forgedSignatureIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAID_EVENT))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("The webhook is reachable without a token - its guard is the signature")
    void webhookNeedsNoBearerToken() throws Exception {
        // A 401 here would mean Stripe could never deliver at all. The 400 above
        // is the route being reached and refusing on its own terms.
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAID_EVENT))
                .andExpect(status().isBadRequest());
    }
}
