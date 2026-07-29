package com.nightgals.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the KYC retention purge job. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
