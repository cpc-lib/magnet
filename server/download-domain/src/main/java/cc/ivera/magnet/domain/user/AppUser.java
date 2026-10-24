package cc.ivera.magnet.domain.user;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AppUser {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private final UUID id;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final String status;
    private final LocalDateTime createdAt;

    public AppUser(UUID id, String username, String passwordHash, Role role, String status, LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
