package com.magnetplatform.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "magnet.storage")
public record StorageProperties(String root) {

    public StorageProperties {
        if (root == null || root.isBlank()) {
            root = "./data";
        }
    }
}
