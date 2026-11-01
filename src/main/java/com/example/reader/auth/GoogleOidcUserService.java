package com.example.reader.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OidcUserService delegate = new OidcUserService();
    private final GoogleOidcAccountService accounts;

    public GoogleOidcUserService(GoogleOidcAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser googleUser = delegate.loadUser(userRequest);
        AppUser appUser = accounts.provision(googleUser);
        var authorities = appUser.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new DefaultOidcUser(authorities, googleUser.getIdToken(), googleUser.getUserInfo(), "email");
    }
}
