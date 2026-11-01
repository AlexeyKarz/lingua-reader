package com.example.reader.auth;

import com.example.reader.metrics.AdminAuditService;
import com.example.reader.metrics.ProductEventService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {
    public static final String NEXT_SESSION_ATTRIBUTE = "mlr.oauth2.next";

    private final UserRepository users;
    private final ProductEventService events;
    private final AdminAuditService audit;

    public OAuth2LoginSuccessHandler(UserRepository users, ProductEventService events, AdminAuditService audit) {
        this.users = users;
        this.events = events;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        users.recordLogin(authentication.getName());
        events.recordSimple("login_succeeded", authentication, Map.of("method", "google_oidc"));
        audit.recordIfAdmin("login_succeeded", authentication, request, "user", authentication.getName(), Map.of("method", "google_oidc"));

        Object requested = request.getSession(false) == null
                ? null
                : request.getSession(false).getAttribute(NEXT_SESSION_ATTRIBUTE);
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(NEXT_SESSION_ATTRIBUTE);
        }
        String next = requested instanceof String value && isSafeRelativePath(value) ? value : "/";
        response.sendRedirect(next);
    }

    static boolean isSafeRelativePath(String value) {
        return value != null
                && value.startsWith("/")
                && !value.startsWith("//")
                && !value.contains("\\")
                && !value.contains("\r")
                && !value.contains("\n");
    }
}
