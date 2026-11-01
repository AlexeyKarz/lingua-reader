package com.example.reader.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleOidcAccountServiceTest {

    @Test
    void linksVerifiedGoogleAccountAndPromotesConfiguredAdmin() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        GoogleOidcProperties properties = new GoogleOidcProperties();
        properties.setAdminEmails("owner@example.test, other@example.test");
        GoogleOidcAccountService service = new GoogleOidcAccountService(users, passwords, properties);
        OidcUser google = googleUser("google-subject", "Owner@Example.Test", true);
        AppUser existing = user(42, "owner@example.test", List.of("USER"));
        AppUser promoted = user(42, "owner@example.test", List.of("ADMIN", "USER"));

        when(users.findEmailByOauthIdentity("google", "google-subject")).thenReturn(Optional.empty());
        when(users.findByEmail("owner@example.test")).thenReturn(Optional.of(existing), Optional.of(promoted));

        service.provision(google);

        verify(users).addRole(42, "USER");
        verify(users).addRole(42, "ADMIN");
        verify(users).linkOauthIdentity(42, "google", "google-subject", "owner@example.test");
        verify(users, never()).createUser(any(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsGoogleAccountWithoutVerifiedEmail() {
        UserRepository users = mock(UserRepository.class);
        GoogleOidcAccountService service = new GoogleOidcAccountService(
                users,
                mock(PasswordEncoder.class),
                new GoogleOidcProperties()
        );

        assertThatThrownBy(() -> service.provision(googleUser("subject", "reader@example.test", false)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("verified email");

        verify(users, never()).findByEmail(any());
    }

    @Test
    void refusesChangedEmailForPreviouslyLinkedSubject() {
        UserRepository users = mock(UserRepository.class);
        when(users.findEmailByOauthIdentity("google", "subject"))
                .thenReturn(Optional.of("original@example.test"));
        GoogleOidcAccountService service = new GoogleOidcAccountService(
                users,
                mock(PasswordEncoder.class),
                new GoogleOidcProperties()
        );

        assertThatThrownBy(() -> service.provision(googleUser("subject", "changed@example.test", true)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("no longer matches");
    }

    private OidcUser googleUser(String subject, String email, boolean verified) {
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn(subject);
        when(user.getEmail()).thenReturn(email);
        when(user.getEmailVerified()).thenReturn(verified);
        when(user.getFullName()).thenReturn("Test Reader");
        return user;
    }

    private AppUser user(long id, String email, List<String> roles) {
        Instant now = Instant.now();
        return new AppUser(id, email, "Test Reader", "hash", true, now, now, null, roles);
    }
}
