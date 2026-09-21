package com.example.reader.user;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record UserLocalStateImportRequest(
        List<SaveVocabularyRequest> vocabulary,
        List<SaveReaderProgressRequest> progress,
        Map<String, JsonNode> preferences
) {}
