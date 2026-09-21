package com.example.reader.auth;

import com.example.reader.security.AdminAccessProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class AdminApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final ApiTokenService apiTokens;
    private final AdminAccessProperties legacyAdmin;

    public AdminApiAuthenticationFilter(ApiTokenService apiTokens, AdminAccessProperties legacyAdmin) {
        this.apiTokens = apiTokens;
        this.legacyAdmin = legacyAdmin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticateApiToken(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateApiToken(HttpServletRequest request) {
        String bearer = resolveBearerToken(request);
        apiTokens.authenticate(bearer)
                .ifPresentOrElse(
                        token -> SecurityContextHolder.getContext().setAuthentication(apiTokenAuthentication(token)),
                        () -> authenticateLegacyToken(request)
                );
    }

    private UsernamePasswordAuthenticationToken apiTokenAuthentication(ApiToken token) {
        List<SimpleGrantedAuthority> authorities = token.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority(SCOPE_AUTHORITY_PREFIX + scope))
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(
                "api-token:" + token.id(),
                "n/a",
                authorities
        );
    }

    private void authenticateLegacyToken(HttpServletRequest request) {
        if (!legacyAdmin.isEnabled()) {
            return;
        }
        String configuredToken = legacyAdmin.getToken();
        if (!StringUtils.hasText(configuredToken)) {
            return;
        }
        String providedToken = resolveToken(request);
        if (!constantTimeEquals(configuredToken, providedToken)) {
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "legacy-admin-token",
                        "n/a",
                        List.of(new SimpleGrantedAuthority(SCOPE_AUTHORITY_PREFIX + ApiTokenService.SCOPE_ADMIN_ALL))
                )
        );
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

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
