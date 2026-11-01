package com.example.reader;

import com.example.reader.translate.properties.*;
import com.example.reader.ingestion.IngestionUploadProperties;
import com.example.reader.auth.AuthBootstrapProperties;
import com.example.reader.auth.GoogleOidcProperties;
import com.example.reader.security.AdminAccessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AuthBootstrapProperties.class,
        GoogleOidcProperties.class,
        AdminAccessProperties.class,
        IngestionUploadProperties.class,
        OpenAITranslateProperties.class,
        OllamaTranslateProperties.class,
        GoogleTranslateProperties.class,
        ProviderProperties.class,
        PromptProperties.class
})
public class MonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
