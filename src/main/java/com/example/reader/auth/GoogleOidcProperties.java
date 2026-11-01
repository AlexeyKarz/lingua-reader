package com.example.reader.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "mlr.auth.google")
public class GoogleOidcProperties {
    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String adminEmails = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    public String getAdminEmails() {
        return adminEmails;
    }

    public void setAdminEmails(String adminEmails) {
        this.adminEmails = adminEmails == null ? "" : adminEmails;
    }

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    public Set<String> normalizedAdminEmails() {
        return Arrays.stream(adminEmails.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
