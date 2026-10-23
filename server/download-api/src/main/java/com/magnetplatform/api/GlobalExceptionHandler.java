package com.magnetplatform.api;

import com.magnetplatform.api.dto.Dtos.ErrorResponse;
import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
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
    ResponseEntity<ErrorResponse> domain(DomainException e, HttpServletResponse response) {
        HttpStatus status = CODE_TO_STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        return error(response, status, e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MultipartException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception e, HttpServletResponse response) {
        return error(response, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请求参数不合法");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ErrorResponse> io(IOException e, HttpServletResponse response) {
        // 连接已不可写时不要再向 SSE 响应写入 JSON 错误体。
        if (e instanceof AsyncRequestNotUsableException
                || DisconnectedClientHelper.isClientDisconnectedException(e)
                || (response.getContentType() != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(
                        MediaType.parseMediaType(response.getContentType())))) {
            log.debug("客户端连接已断开", e);
            return null;
        }
        return internal(e, response);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception e, HttpServletResponse response) {
        log.error("未处理异常", e);
        return error(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "服务器内部错误");
    }

    private ResponseEntity<ErrorResponse> error(HttpServletResponse response, HttpStatus status,
                                               String code, String message) {
        if (response.isCommitted()) {
            return null;
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message));
    }
}
