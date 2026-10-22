package com.magnetplatform.application;

import com.magnetplatform.domain.user.Role;

import java.util.UUID;

/** JWT 解析出的调用方身份；所有读写接口以此为准，不接受客户端任意指定用户 ID（规格 §4.1） */
public record JwtPrincipal(UUID userId, Role role) {

    public boolean admin() {
        return role == Role.ADMIN;
    }
}
