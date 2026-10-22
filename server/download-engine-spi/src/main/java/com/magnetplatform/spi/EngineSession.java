package com.magnetplatform.spi;

/** 引擎会话句柄的传输对象；id 为引擎侧会话标识，不作为数据库永久主键（规格 §7.1） */
public record EngineSession(String engineSessionId,
                            EngineKind engineKind,
                            SessionState state) {
}
