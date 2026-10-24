package cc.ivera.magnet.application.port;

import java.util.UUID;

public interface RateLimiter {

    /** 增加并返回用户当日解析计数（首次调用时设置 24h 过期） */
    long hitParseToday(UUID userId);
}
