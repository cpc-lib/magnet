package cc.ivera.magnet.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "cc.ivera.magnet")
@ConfigurationPropertiesScan(basePackages = "cc.ivera.magnet")
public class MagnetPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MagnetPlatformApplication.class, args);
    }
}
