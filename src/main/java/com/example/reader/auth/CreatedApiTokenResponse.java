package com.example.reader.auth;

public record CreatedApiTokenResponse(ApiTokenDto token, String secret) {}
