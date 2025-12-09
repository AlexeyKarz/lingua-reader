package com.example.reader.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> badRequest(IllegalArgumentException ex, WebRequest req) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiError> validationErrors(Exception ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex, req, false);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dbErrors(DataAccessException ex, WebRequest req) {
        log.error("DB error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex, req, true);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception ex, WebRequest req) {
        log.error("Unhandled error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex, req, true);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, Exception ex, WebRequest req, boolean includeMessage) {
        String path = req.getDescription(false).replace("uri=", "");
        String message = includeMessage ? ex.getMessage() : "Request validation failed";
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(body);
    }

    public record ApiError(int status, String error, String message, String path) {}
}
