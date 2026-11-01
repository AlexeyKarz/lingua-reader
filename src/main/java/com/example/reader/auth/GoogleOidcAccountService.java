package com.example.reader.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class GoogleOidcAccountService {
    private static final String PROVIDER = "google";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOidcProperties properties;

    public GoogleOidcAccountService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            GoogleOidcProperties properties
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public AppUser provision(OidcUser oidcUser) {
        String subject = oidcUser.getSubject();
        String email = UserRepository.normalizeEmail(oidcUser.getEmail());
        if (!StringUtils.hasText(subject) || !StringUtils.hasText(email) || !Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw oauthError("Google did not provide a verified email address");
        }

        var linkedEmail = users.findEmailByOauthIdentity(PROVIDER, subject);
        if (linkedEmail.isPresent() && !email.equals(UserRepository.normalizeEmail(linkedEmail.get()))) {
            throw oauthError("The Google account email no longer matches the linked account");
        }

        AppUser user = linkedEmail
                .flatMap(users::findByEmail)
                .or(() -> users.findByEmail(email))
                .orElseGet(() -> {
                    long userId = users.createUser(
                            email,
                            oidcUser.getFullName(),
                            passwordEncoder.encode(UUID.randomUUID().toString()),
                            true
                    );
                    users.addRole(userId, "USER");
                    return users.findByEmail(email).orElseThrow();
                });

        if (!user.enabled()) {
            throw oauthError("This account is disabled");
        }

        users.addRole(user.id(), "USER");
        if (properties.normalizedAdminEmails().contains(email)) {
            users.addRole(user.id(), "ADMIN");
        }
        users.updateDisplayNameIfMissing(user.id(), oidcUser.getFullName());
        users.linkOauthIdentity(user.id(), PROVIDER, subject, email);
        return users.findByEmail(email).orElseThrow();
    }

    private OAuth2AuthenticationException oauthError(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_google_account"), description);
    }
}
