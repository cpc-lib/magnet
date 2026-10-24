package cc.ivera.magnet.application;

import cc.ivera.magnet.application.port.UserRepository;
import cc.ivera.magnet.domain.common.DomainException;
import cc.ivera.magnet.domain.common.ErrorCodes;
import cc.ivera.magnet.domain.user.AppUser;
import cc.ivera.magnet.domain.user.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[\\w.-]{3,32}$");

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository users) {
        this.users = users;
    }

    /** 首个注册用户为 ADMIN，其余为 USER */
    @Transactional
    public AppUser register(String username, String rawPassword) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new DomainException(ErrorCodes.VALIDATION, "用户名需为 3-32 位字母、数字或 . _ -");
        }
        if (rawPassword == null || rawPassword.length() < 8 || rawPassword.length() > 72) {
            throw new DomainException(ErrorCodes.VALIDATION, "密码长度需为 8-72 个字符");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new DomainException(ErrorCodes.CONFLICT, "用户名已存在");
        }
        Role role = users.count() == 0 ? Role.ADMIN : Role.USER;
        AppUser user = new AppUser(UUID.randomUUID(), username, encoder.encode(rawPassword),
                role, AppUser.STATUS_ACTIVE, LocalDateTime.now());
        try {
            users.insert(user);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCodes.CONFLICT, "用户名已存在");
        }
        return user;
    }

    public AppUser login(String username, String rawPassword) {
        AppUser user = users.findByUsername(username == null ? "" : username)
                .orElseThrow(() -> new DomainException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误"));
        if (!AppUser.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new DomainException(ErrorCodes.UNAUTHORIZED, "账号已禁用");
        }
        if (rawPassword == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new DomainException(ErrorCodes.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }
}
