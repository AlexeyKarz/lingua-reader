package com.example.reader.translate.providers;

import org.springframework.stereotype.Component;

// TODO replace with real HTTP client to a local model or cloud provider
@Component
public class ProviderClient {
    public String literal(String text, String source, String target) {
        // Placeholder for a provider call; keep fast for now
        return "[" + source + "→" + target + "] " + text;
    }
}
