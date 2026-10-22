package com.magnetplatform.infrastructure;

import com.magnetplatform.application.port.UserRepository;
import com.magnetplatform.domain.user.AppUser;
import com.magnetplatform.infrastructure.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AppUser> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    @Override
    public Optional<AppUser> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username));
    }

    @Override
    public void insert(AppUser user) {
        mapper.insert(user);
    }

    @Override
    public long count() {
        return mapper.count();
    }
}
