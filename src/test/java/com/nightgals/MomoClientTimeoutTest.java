package com.nightgals;

import com.nightgals.billing.momo.MomoClient;
import com.nightgals.config.MomoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An MTN that accepts the connection and then never answers.
 *
 * <p>Before the timeout existed, either call below would block until the test
 * runner gave up, and in production a reconciliation sweep would stall behind
 * it. Now each has to come back inside the configured timeout, reporting MTN as
 * unavailable rather than throwing.
 */
class MomoClientTimeoutTest {

    private ServerSocket silentServer;
    // Written by the accepting thread, read on cleanup.
    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private Thread acceptor;

    @BeforeEach
    void startSilentServer() throws IOException {
        silentServer = new ServerSocket(0);
        acceptor = Thread.ofVirtual().start(() -> {
            while (!silentServer.isClosed()) {
                try {
                    // Accept and keep the socket open, but never write a byte back.
                    held.add(silentServer.accept());
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopSilentServer() throws IOException {
        silentServer.close();
        for (Socket socket : held) {
            socket.close();
        }
    }

    private MomoClient clientWithTimeout(Duration timeout) {
        return new MomoClient(new MomoProperties(
                "http://127.0.0.1:" + silentServer.getLocalPort(),
                "subscription-key", "api-user", "api-key", "sandbox", "EUR",
                null, Duration.ofMinutes(5), "0 */2 * * * *", Duration.ofHours(1), null,
                timeout));
    }

    @Test
    void statusGivesUpInsteadOfHanging() {
        MomoClient client = clientWithTimeout(Duration.ofSeconds(1));

        long started = System.nanoTime();
        var status = client.status(UUID.randomUUID());
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(status).isEmpty();
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void requestToPayReportsUnavailableInsteadOfHanging() {
        MomoClient client = clientWithTimeout(Duration.ofSeconds(1));

        long started = System.nanoTime();
        boolean accepted = client.requestToPay(UUID.randomUUID(), "237689686224", 1000,
                "external-id", "Nightgals test");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(accepted).isFalse();
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }
}
