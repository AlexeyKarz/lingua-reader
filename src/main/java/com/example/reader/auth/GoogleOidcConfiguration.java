package com.example.reader.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "mlr.auth.google", name = "enabled", havingValue = "true")
public class GoogleOidcConfiguration {

    @Bean
    ClientRegistrationRepository googleClientRegistrationRepository(GoogleOidcProperties properties) {
        if (!StringUtils.hasText(properties.getClientId()) || !StringUtils.hasText(properties.getClientSecret())) {
            throw new IllegalStateException(
                    "Google OAuth is enabled but MLR_GOOGLE_OAUTH_CLIENT_ID/CLIENT_SECRET are not configured"
            );
        }

        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientName("Google")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .issuerUri("https://accounts.google.com")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }
}
