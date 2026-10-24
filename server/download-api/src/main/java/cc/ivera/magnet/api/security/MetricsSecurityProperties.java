package cc.ivera.magnet.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "magnet.metrics")
public record MetricsSecurityProperties(String scrapeUser, String scrapePasswordFile) {
    public MetricsSecurityProperties {
        if (scrapeUser == null || scrapeUser.isBlank()) {
            scrapeUser = "prometheus";
        }
        if (scrapePasswordFile == null || scrapePasswordFile.isBlank()) {
            scrapePasswordFile = "./infra/.secrets/prometheus-scrape-password";
        }
    }
}
