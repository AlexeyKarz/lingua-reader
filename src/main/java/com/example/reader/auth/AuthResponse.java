package com.example.reader.auth;

import java.util.List;

public record AuthResponse(boolean authenticated, UserDto user) {
    public static AuthResponse anonymous() {
        return new AuthResponse(false, null);
    }

    public record UserDto(long id, String email, String displayName, List<String> roles) {}
}
