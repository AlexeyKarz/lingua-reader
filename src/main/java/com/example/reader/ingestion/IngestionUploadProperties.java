package com.example.reader.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "mlr.ingestion.upload")
public class IngestionUploadProperties {
    private Path dir = Path.of(System.getProperty("java.io.tmpdir"), "multilang-reader", "ingestion-uploads");

    public Path getDir() {
        return dir;
    }

    public void setDir(Path dir) {
        if (dir != null) {
            this.dir = dir;
        }
    }
}
