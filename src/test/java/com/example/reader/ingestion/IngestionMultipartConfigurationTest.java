package com.example.reader.ingestion;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionMultipartConfigurationTest {

    @Test
    void raisesTomcatPartLimitForMetadataRichUploads() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
                new IngestionMultipartConfiguration().ingestionMultipartPartLimit(50);

        customizer.customize(factory);
        Connector connector = new Connector();
        factory.getTomcatConnectorCustomizers().forEach(item -> item.customize(connector));

        assertThat(connector.getMaxPartCount()).isEqualTo(50);
    }
}
