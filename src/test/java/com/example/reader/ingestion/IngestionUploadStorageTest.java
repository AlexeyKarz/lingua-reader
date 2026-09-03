package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionUploadStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void storesEpubUploadsUnderConfiguredDirectory() throws Exception {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = new IngestionUploadStorage(properties);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../Unsafe Name.epub",
                "application/epub+zip",
                "epub-content".getBytes(StandardCharsets.UTF_8)
        );

        String fileSpec = storage.store(file);

        Path stored = Path.of(URI.create(fileSpec));
        assertThat(stored).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).endsWith("-Unsafe-Name.epub");
        assertThat(Files.readString(stored)).isEqualTo("epub-content");
    }

    @Test
    void rejectsNonEpubUploads() {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = new IngestionUploadStorage(properties);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "book.txt",
                "text/plain",
                "not epub".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only EPUB files are supported");
    }
}
