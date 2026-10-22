package com.magnetplatform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.magnetplatform")
@ConfigurationPropertiesScan(basePackages = "com.magnetplatform")
public class MagnetPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MagnetPlatformApplication.class, args);
    }
}
