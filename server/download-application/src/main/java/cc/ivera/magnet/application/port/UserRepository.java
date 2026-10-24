package cc.ivera.magnet.application.port;

import cc.ivera.magnet.domain.user.AppUser;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<AppUser> findById(UUID id);

    Optional<AppUser> findByUsername(String username);

    void insert(AppUser user);

    long count();
}
