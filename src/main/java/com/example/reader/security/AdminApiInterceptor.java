package com.example.reader.security;

import com.example.reader.auth.ApiTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AdminApiInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final AdminAccessProperties properties;
    private final ApiTokenService apiTokens;

    public AdminApiInterceptor(AdminAccessProperties properties, ApiTokenService apiTokens) {
        this.properties = properties;
        this.apiTokens = apiTokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (isAuthenticatedAdmin()) {
            return true;
        }

        String bearerToken = resolveBearerToken(request);
        if (apiTokens.allows(bearerToken, requiredScope(request))) {
            return true;
        }

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

    private boolean isAuthenticatedAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = resolveBearerToken(request);
        if (StringUtils.hasText(bearer)) return bearer;
        return request.getHeader(ADMIN_TOKEN_HEADER);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(auth) && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private String requiredScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/admin/ingest")) {
            return ApiTokenService.SCOPE_ADMIN_INGEST;
        }
        if (path != null && path.startsWith("/api/admin/metrics")) {
            return ApiTokenService.SCOPE_ADMIN_METRICS;
        }
        return ApiTokenService.SCOPE_ADMIN_ALL;
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
