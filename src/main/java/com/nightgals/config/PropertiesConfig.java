package com.nightgals.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class, AppProperties.class,
        MonetizationProperties.class, EarningsProperties.class})
public class PropertiesConfig {
}
