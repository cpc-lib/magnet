package cc.ivera.magnet.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class MetricsUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsUserDetailsService.class);

    private final String username;
    private final String encodedPassword;

    public MetricsUserDetailsService(MetricsSecurityProperties properties) {
        this.username = properties.scrapeUser();
        String rawPassword = loadPassword(properties.scrapePasswordFile());
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        this.encodedPassword = encoder.encode(rawPassword);
    }

    private Path resolvePath(String filePath) {
        Path path = Path.of(filePath);
        if (Files.exists(path)) {
            return path;
        }
        Path parent = Path.of("..").resolve(filePath);
        if (Files.exists(parent)) {
            return parent;
        }
        Path grandParent = Path.of("../..").resolve(filePath);
        if (Files.exists(grandParent)) {
            return grandParent;
        }
        return path;
    }

    private String loadPassword(String filePath) {
        try {
            Path path = resolvePath(filePath);
            if (!Files.exists(path)) {
                log.warn("Metrics scrape password file does not exist at {}. Basic Auth will fail closed.", path.toAbsolutePath());
                return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
            }
            String content = Files.readString(path).trim();
            if (content.isBlank()) {
                log.warn("Metrics scrape password file at {} is blank. Basic Auth will fail closed.", path.toAbsolutePath());
                return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
            }
            return content;
        } catch (IOException e) {
            log.warn("Failed to read metrics scrape password file at {}. Basic Auth will fail closed.", filePath, e);
            return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (!this.username.equals(username)) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return User.withUsername(this.username)
                .password(this.encodedPassword)
                .roles("METRICS")
                .build();
    }
}
