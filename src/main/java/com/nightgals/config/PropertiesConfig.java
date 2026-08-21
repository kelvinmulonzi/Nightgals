package com.nightgals.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class, AppProperties.class,
        MonetizationProperties.class, EarningsProperties.class, OtpProperties.class,
        NotificationProperties.class, CreatorPackageProperties.class,
        CallProperties.class, LiveProperties.class, MomoProperties.class,
        StripeProperties.class, GiftProperties.class, LiveKitProperties.class,
        GoogleProperties.class})
public class PropertiesConfig {
}
