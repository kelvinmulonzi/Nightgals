package com.nightgals.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background work.
 *
 * <p>{@code @EnableScheduling} drives the KYC retention purge, the earnings
 * release sweep and the one-time-code cleanup. {@code @EnableAsync} is what lets
 * receipts and notifications be sent off the request thread - Gmail's SMTP
 * handshake is slow enough to be felt if a checkout waits on it.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
