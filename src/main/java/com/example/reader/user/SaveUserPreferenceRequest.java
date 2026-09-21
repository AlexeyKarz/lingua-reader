package com.example.reader.user;

import com.fasterxml.jackson.databind.JsonNode;

public record SaveUserPreferenceRequest(JsonNode value) {}
