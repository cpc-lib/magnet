package com.magnetplatform.api.security;

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

@Service
public class MetricsUserDetailsService implements UserDetailsService {

    private final String username;
    private final String encodedPassword;

    public MetricsUserDetailsService(MetricsSecurityProperties properties) {
        this.username = properties.scrapeUser();
        String rawPassword = loadPassword(properties.scrapePasswordFile());
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        this.encodedPassword = encoder.encode(rawPassword);
    }

    private String loadPassword(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw new IllegalStateException("Metrics scrape password file does not exist: " + path.toAbsolutePath());
            }
            String content = Files.readString(path).trim();
            if (content.isBlank()) {
                throw new IllegalStateException("Metrics scrape password file is blank: " + path.toAbsolutePath());
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read metrics scrape password file: " + filePath, e);
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
