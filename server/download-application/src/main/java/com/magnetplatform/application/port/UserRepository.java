package com.magnetplatform.application.port;

import com.magnetplatform.domain.user.AppUser;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<AppUser> findById(UUID id);

    Optional<AppUser> findByUsername(String username);

    void insert(AppUser user);

    long count();
}
