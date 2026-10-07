package com.wordplay.common.exception;

import com.wordplay.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        log.warn("BusinessException: {} - {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.error(e.getErrorCode().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getDefaultMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT.name(), message));
    }

    /** 요청 형식 오류(깨진 JSON, 잘못된 enum 값, 숫자가 아닌 파라미터 등) — 클라이언트 잘못이므로 400. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception e) {
        log.warn("Bad request: {}", e.getMessage());
        return error(ErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(NoResourceFoundException e) {
        log.warn("Not found: /{}", e.getResourcePath());
        return error(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMessage());
        return error(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<Object>> error(ErrorCode code) {
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.error(code.name(), code.getDefaultMessage()));
    }
}
