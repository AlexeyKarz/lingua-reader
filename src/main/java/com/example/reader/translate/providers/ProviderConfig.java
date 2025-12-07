package com.example.reader.translate.providers;

import com.example.reader.translate.OpenAITranslateProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "translate.provider", havingValue = "openai")
    public ProviderClient openAiProvider(OpenAITranslateProperties props, WebClient.Builder builder) {
        return new OpenAIProviderClient(props, builder);
    }

    // Later:
    // @Bean
    // @ConditionalOnProperty(name = "translate.provider", havingValue = "ollama")
    // public ProviderClient ollamaProvider(OllamaProperties props, WebClient.Builder builder) {
    //     return new OllamaProviderClient(props, builder);
    // }

    @Bean
    @ConditionalOnMissingBean(ProviderClient.class)
    public ProviderClient stubProvider() {
        return new StubProviderClient();
    }
}
