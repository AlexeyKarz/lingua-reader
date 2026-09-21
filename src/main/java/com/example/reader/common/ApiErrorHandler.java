package com.example.reader.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> badRequest(IllegalArgumentException ex, WebRequest req) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> responseStatus(ResponseStatusException ex, WebRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null ? defaultMessage(status) : ex.getReason();
        return ResponseEntity.status(status).body(new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                requestPath(req)
        ));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MultipartException.class
    })
    public ResponseEntity<ApiError> validationErrors(Exception ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex, req, false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex, WebRequest req) {
        log.warn(
                "Method not allowed: method={} path={} supported={}",
                ex.getMethod(),
                requestPath(req),
                ex.getSupportedHttpMethods()
        );
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex, req, false);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dbErrors(DataAccessException ex, WebRequest req) {
        log.error("DB error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex, req, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception ex, WebRequest req) {
        log.error("Unhandled error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex, req, false);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, Exception ex, WebRequest req, boolean includeMessage) {
        String path = requestPath(req);
        String message = includeMessage ? ex.getMessage() : defaultMessage(status);
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(body);
    }

    private String requestPath(WebRequest req) {
        return req.getDescription(false).replace("uri=", "");
    }

    private String defaultMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Request validation failed";
            case METHOD_NOT_ALLOWED -> "Request method is not supported";
            case INTERNAL_SERVER_ERROR -> "Unexpected server error";
            default -> status.getReasonPhrase();
        };
    }

    public record ApiError(int status, String error, String message, String path) {}
}
