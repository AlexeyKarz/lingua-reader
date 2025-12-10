package com.example.reader.translate;

import com.example.reader.translate.properties.GoogleTranslateProperties;
import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.OllamaTranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import com.example.reader.translate.properties.ProviderProperties;
import com.example.reader.translate.providers.GoogleProviderClient;
import com.example.reader.translate.providers.OpenAIProviderClient;
import com.example.reader.translate.providers.ProviderClient;
import com.example.reader.translate.providers.StubProviderClient;
import com.example.reader.translate.providers.OllamaProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({
        ProviderProperties.class,
        OpenAITranslateProperties.class,
        GoogleTranslateProperties.class,
        PromptProperties.class
})
public class ProviderConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ProviderConfiguration.class);

    @Bean
    public ProviderClient providerClient(
            ProviderProperties props,
            OpenAITranslateProperties openAIProps,
            GoogleTranslateProperties googleProps,
            OllamaTranslateProperties ollamaProps,
            PromptProperties promptProps,
            WebClient.Builder builder
    ) {
        String which = props.getProvider() == null ? "stub" : props.getProvider().toLowerCase();
        return switch (which) {
            case "openai" -> new OpenAIProviderClient(openAIProps, promptProps, builder);
            case "google", "gemini" -> new GoogleProviderClient(googleProps, promptProps, builder);
            case "ollama" -> new OllamaProviderClient(ollamaProps, builder);
            default -> new StubProviderClient();
        };
    }
}
