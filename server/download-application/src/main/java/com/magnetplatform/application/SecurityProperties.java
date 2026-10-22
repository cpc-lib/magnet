package com.magnetplatform.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "magnet.security")
public record SecurityProperties(String jwtSecret, Duration jwtTtl) {

    public SecurityProperties {
        if (jwtTtl == null) {
            jwtTtl = Duration.ofHours(12);
        }
    }
}
