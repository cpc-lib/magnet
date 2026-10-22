package com.magnetplatform.api;

import com.magnetplatform.api.dto.Dtos.ErrorResponse;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

/** 领域错误码 → HTTP 状态集中映射（规格 §9.1），响应体 {code,message} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, HttpStatus> CODE_TO_STATUS = Map.ofEntries(
            Map.entry(ErrorCodes.VALIDATION, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCodes.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCodes.FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCodes.CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCodes.WORKER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCodes.INVALID_MAGNET, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.INVALID_TORRENT, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.INVALID_PATH, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(ErrorCodes.INPUT_LOST, HttpStatus.GONE),
            Map.entry(ErrorCodes.EXPORT_IO_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCodes.METADATA_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT));

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> domain(DomainException e) {
        HttpStatus status = CODE_TO_STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(status).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MultipartException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCodes.VALIDATION, "请求参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL", "服务器内部错误"));
    }
}
