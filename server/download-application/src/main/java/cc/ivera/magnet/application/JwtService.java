package cc.ivera.magnet.application;

import cc.ivera.magnet.domain.common.DomainException;
import cc.ivera.magnet.domain.common.ErrorCodes;
import cc.ivera.magnet.domain.user.AppUser;
import cc.ivera.magnet.domain.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** HS256 JWT：sub=userId，role claim，默认 12h 过期 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(SecurityProperties properties) {
        byte[] secret = properties.jwtSecret() == null
                ? new byte[0]
                : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("magnet.security.jwt-secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = properties.jwtTtl();
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public JwtPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new JwtPrincipal(UUID.fromString(claims.getSubject()),
                    Role.valueOf(claims.get("role", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new DomainException(ErrorCodes.UNAUTHORIZED, "访问令牌无效或已过期");
        }
    }
}
