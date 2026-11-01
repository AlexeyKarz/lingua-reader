package com.example.reader.auth;

import com.example.reader.metrics.ProductEventService;
import com.example.reader.metrics.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.io.IOException;

@RestController
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository users;
    private final ProductEventService events;
    private final AdminAuditService audit;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final GoogleOidcProperties googleOidc;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CurrentUserService currentUserService,
            UserRepository users,
            ProductEventService events,
            AdminAuditService audit,
            LoginAttemptLimiter loginAttemptLimiter,
            GoogleOidcProperties googleOidc
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.currentUserService = currentUserService;
        this.users = users;
        this.events = events;
        this.audit = audit;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.googleOidc = googleOidc;
    }

    @GetMapping("/api/me")
    public AuthResponse me(Authentication authentication) {
        return currentUserService.from(authentication);
    }

    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "parameterName", token.getParameterName(),
                "headerName", token.getHeaderName(),
                "token", token.getToken()
        );
    }

    @GetMapping("/api/auth/providers")
    public Map<String, Boolean> providers() {
        return Map.of("google", googleOidc.isConfigured());
    }

    @GetMapping("/api/auth/oauth2/google")
    public void googleLogin(
            @RequestParam(name = "next", defaultValue = "/") String next,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (!googleOidc.isConfigured()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Google sign-in is not configured");
            return;
        }
        String safeNext = OAuth2LoginSuccessHandler.isSafeRelativePath(next) ? next : "/";
        request.getSession(true).setAttribute(OAuth2LoginSuccessHandler.NEXT_SESSION_ATTRIBUTE, safeNext);
        response.sendRedirect("/oauth2/authorization/google");
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(
            @RequestBody LoginRequest login,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String email = UserRepository.normalizeEmail(login == null ? null : login.email());
        String password = login == null ? null : login.password();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and password are required");
        }
        loginAttemptLimiter.checkAllowed(email, request.getRemoteAddr());

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password)
            );
        } catch (AuthenticationException ex) {
            loginAttemptLimiter.recordFailure(email, request.getRemoteAddr());
            events.record(
                    "login_failed",
                    null,
                    null,
                    null,
                    null,
                    "login",
                    null,
                    false,
                    null,
                    401,
                    "invalid_credentials",
                    null,
                    null,
                    Map.of()
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        request.getSession(true);
        try {
            request.changeSessionId();
        } catch (RuntimeException ignored) {
            // Session fixation protection is best-effort for non-container test requests.
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        users.recordLogin(authentication.getName());
        loginAttemptLimiter.recordSuccess(email, request.getRemoteAddr());
        events.recordSimple("login_succeeded", authentication, Map.of());
        audit.recordIfAdmin("login_succeeded", authentication, request, "user", authentication.getName(), Map.of());

        return currentUserService.from(authentication);
    }

    @PostMapping("/api/auth/logout")
    public AuthResponse logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        audit.recordIfAdmin("logout", authentication, request, "user", authentication == null ? null : authentication.getName(), Map.of());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        events.recordSimple("logout", authentication, Map.of());
        return AuthResponse.anonymous();
    }
}
