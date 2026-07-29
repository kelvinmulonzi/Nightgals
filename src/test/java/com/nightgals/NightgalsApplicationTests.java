package com.nightgals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application against a throwaway Postgres container. This is
 * the test that catches Flyway/entity drift, since ddl-auto=validate runs here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NightgalsApplicationTests {

    @Test
    void contextLoads() {
    }
}
