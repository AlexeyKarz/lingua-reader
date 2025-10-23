package com.example.reader.translate;

import com.example.reader.translate.providers.ProviderClient;
import org.springframework.stereotype.Service;

// TODO inject cache (e.g., Caffeine) if needed later
@Service
public class TranslateService {

    private final ProviderClient provider;

    public TranslateService(ProviderClient provider) {
        this.provider = provider;
    }

    public String word(String text, String source, String target) {
        // TODO: simple dictionary first; fallback to provider
        return provider.literal(text, source, target);
    }

    public String sentence(String text, String source, String target) {
        // TODO: add circuit breaker & timeout around provider call if needed (even in monolith)
        return provider.literal(text, source, target);
    }
}
