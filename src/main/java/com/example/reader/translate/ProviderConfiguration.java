package com.example.reader.translate;

import com.example.reader.translate.properties.GoogleTranslateProperties;
import com.example.reader.translate.properties.OpenAITranslateProperties;
import com.example.reader.translate.properties.OllamaTranslateProperties;
import com.example.reader.translate.properties.PromptProperties;
import com.example.reader.translate.properties.ProviderProperties;
import com.example.reader.translate.properties.TranslateLimitProperties;
import com.example.reader.translate.providers.GoogleProviderClient;
import com.example.reader.translate.providers.LimitedProviderClient;
import com.example.reader.translate.providers.OpenAIProviderClient;
import com.example.reader.translate.providers.ProviderClient;
import com.example.reader.translate.providers.StubProviderClient;
import com.example.reader.translate.providers.OllamaProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
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
        OllamaTranslateProperties.class,
        PromptProperties.class,
        TranslateLimitProperties.class
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
            TranslateLimitProperties limitProps,
            WebClient.Builder builder,
            MeterRegistry registry
    ) {
        String which = props.getProvider() == null ? "stub" : props.getProvider().toLowerCase();
        ProviderClient provider = switch (which) {
            case "openai" -> new OpenAIProviderClient(openAIProps, promptProps, builder, registry);
            case "google", "gemini" -> new GoogleProviderClient(googleProps, promptProps, builder, registry);
            case "ollama" -> new OllamaProviderClient(ollamaProps, builder, registry);
            default -> new StubProviderClient();
        };
        if (!"stub".equals(which) && limitProps.isEnabled()) {
            log.info(
                    "Translation provider limits enabled: perIpDaily={}, globalDaily={}, maxTextChars={}",
                    limitProps.getPerIpDaily(),
                    limitProps.getGlobalDaily(),
                    limitProps.getMaxTextChars()
            );
            return new LimitedProviderClient(provider, limitProps);
        }
        return provider;
    }
}
