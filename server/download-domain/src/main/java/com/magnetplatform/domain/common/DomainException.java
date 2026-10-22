package com.magnetplatform.domain.common;

/** 领域异常：code 由 API 层映射为 HTTP 状态码（规格 §9.1） */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
