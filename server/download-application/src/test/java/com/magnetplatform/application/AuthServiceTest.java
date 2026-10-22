package com.magnetplatform.application;

import com.magnetplatform.application.port.UserRepository;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import com.magnetplatform.domain.user.AppUser;
import com.magnetplatform.domain.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceTest {

    static class InMemoryUsers implements UserRepository {
        final Map<UUID, AppUser> byId = new LinkedHashMap<>();

        public Optional<AppUser> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<AppUser> findByUsername(String username) {
            return byId.values().stream().filter(u -> u.getUsername().equals(username)).findFirst();
        }

        public void insert(AppUser user) {
            byId.put(user.getId(), user);
        }

        public long count() {
            return byId.size();
        }
    }

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(new InMemoryUsers());
    }

    @Test
    void firstUserIsAdmin_secondIsUser() {
        AppUser first = service.register("alice", "password-1");
        AppUser second = service.register("bob", "password-2");
        assertEquals(Role.ADMIN, first.getRole());
        assertEquals(Role.USER, second.getRole());
    }

    @Test
    void duplicateUsername_conflict() {
        service.register("alice", "password-1");
        assertCode(ErrorCodes.CONFLICT, () -> service.register("alice", "password-2"));
    }

    @Test
    void invalidUsername_validation() {
        assertCode(ErrorCodes.VALIDATION, () -> service.register("ab", "password-1"));
        assertCode(ErrorCodes.VALIDATION, () -> service.register("bad name!", "password-1"));
    }

    @Test
    void shortPassword_validation() {
        assertCode(ErrorCodes.VALIDATION, () -> service.register("alice", "short"));
    }

    @Test
    void loginSuccessAndWrongPassword() {
        service.register("alice", "password-1");
        assertEquals("alice", service.login("alice", "password-1").getUsername());
        assertCode(ErrorCodes.UNAUTHORIZED, () -> service.login("alice", "wrong-pass"));
        assertCode(ErrorCodes.UNAUTHORIZED, () -> service.login("nobody", "password-1"));
    }

    private static void assertCode(String code, Executable exec) {
        DomainException ex = assertThrows(DomainException.class, exec);
        assertEquals(code, ex.code());
    }
}
