package com.example.reader.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionMultipartConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> ingestionMultipartPartLimit(
            @Value("${mlr.ingestion.upload.max-part-count:50}") int maxPartCount
    ) {
        if (maxPartCount < 1) {
            throw new IllegalArgumentException("mlr.ingestion.upload.max-part-count must be positive");
        }
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setMaxPartCount(maxPartCount)
        );
    }
}
