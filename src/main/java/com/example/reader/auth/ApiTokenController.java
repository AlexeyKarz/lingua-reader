package com.example.reader.auth;

import com.example.reader.metrics.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tokens")
public class ApiTokenController {
    private final CurrentUserService currentUser;
    private final ApiTokenService apiTokens;
    private final AdminAuditService audit;

    public ApiTokenController(CurrentUserService currentUser, ApiTokenService apiTokens, AdminAuditService audit) {
        this.currentUser = currentUser;
        this.apiTokens = apiTokens;
        this.audit = audit;
    }

    @GetMapping
    public List<ApiTokenDto> list(Authentication authentication) {
        return apiTokens.list(currentUser.requireUser(authentication).id());
    }

    @PostMapping
    public CreatedApiTokenResponse create(
            Authentication authentication,
            @RequestBody CreateApiTokenRequest request,
            HttpServletRequest servletRequest
    ) {
        CreatedApiTokenResponse created = apiTokens.create(currentUser.requireUser(authentication).id(), request);
        audit.record(
                "api_token_created",
                authentication,
                servletRequest,
                "api_token",
                String.valueOf(created.token().id()),
                java.util.Map.of("name", created.token().name(), "scopes", created.token().scopes())
        );
        return created;
    }

    @PostMapping("/{id}/revoke")
    public void revoke(Authentication authentication, @PathVariable long id, HttpServletRequest servletRequest) {
        apiTokens.revoke(currentUser.requireUser(authentication).id(), id);
        audit.record("api_token_revoked", authentication, servletRequest, "api_token", String.valueOf(id), java.util.Map.of());
    }
}
