package com.example.reader;

import com.example.reader.translate.properties.OllamaTranslateProperties;
import com.example.reader.translate.properties.OpenAITranslateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OpenAITranslateProperties.class, OllamaTranslateProperties.class})
public class MonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
