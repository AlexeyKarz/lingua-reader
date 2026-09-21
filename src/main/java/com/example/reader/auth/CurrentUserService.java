package com.example.reader.auth;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {
    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public AuthResponse from(Authentication authentication) {
        if (!isRealUser(authentication)) {
            return AuthResponse.anonymous();
        }
        return users.findByEmail(authentication.getName())
                .map(user -> new AuthResponse(
                        true,
                        new AuthResponse.UserDto(
                                user.id(),
                                user.email(),
                                user.displayName(),
                                user.roles()
                        )
                ))
                .orElseGet(AuthResponse::anonymous);
    }

    public AppUser requireUser(Authentication authentication) {
        if (!isRealUser(authentication)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    private boolean isRealUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
