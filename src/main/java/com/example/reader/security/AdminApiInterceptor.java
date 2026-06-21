package com.example.reader.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminApiInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final AdminAccessProperties properties;

    public AdminApiInterceptor(AdminAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return false;
        }

        String configuredToken = properties.getToken();
        if (!StringUtils.hasText(configuredToken)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Admin API token is not configured");
            return false;
        }

        String providedToken = resolveToken(request);
        if (!constantTimeEquals(configuredToken, providedToken)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid admin token");
            return false;
        }

        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(auth) && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        return request.getHeader(ADMIN_TOKEN_HEADER);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
