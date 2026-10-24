package cc.ivera.magnet.infrastructure;

import cc.ivera.magnet.application.port.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** 每日解析配额：INCR rl:parse:{userId}:{yyyyMMdd}，首次写入设 24h 过期 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long hitParseToday(UUID userId) {
        String key = "rl:parse:" + userId + ":" + LocalDate.now(ZoneOffset.UTC);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(24));
        }
        return count == null ? 0 : count;
    }
}
