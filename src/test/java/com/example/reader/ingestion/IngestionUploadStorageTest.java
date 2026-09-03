package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionUploadStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void storesEpubUploadsUnderConfiguredDirectory() throws Exception {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = new IngestionUploadStorage(properties, new PlainTextEpubConverter());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../Unsafe Name.epub",
                "application/epub+zip",
                "epub-content".getBytes(StandardCharsets.UTF_8)
        );

        String fileSpec = storage.store(file, "Unsafe Book", "en");

        Path stored = Path.of(URI.create(fileSpec));
        assertThat(stored).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).endsWith("-Unsafe-Name.epub");
        assertThat(Files.readString(stored)).isEqualTo("epub-content");
    }

    @Test
    void convertsPlainTextUploadsToEpub() throws Exception {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = new IngestionUploadStorage(properties, new PlainTextEpubConverter());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "plain book.txt",
                "text/plain",
                """
                Chapter 1

                First paragraph.

                Second paragraph with <unsafe> characters & punctuation.
                """.getBytes(StandardCharsets.UTF_8)
        );

        String fileSpec = storage.store(file, "Plain Book", "en");

        Path stored = Path.of(URI.create(fileSpec));
        assertThat(stored).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).endsWith("-plain-book.epub");
        try (ZipFile zip = new ZipFile(stored.toFile())) {
            assertThat(zip.getEntry("mimetype")).isNotNull();
            assertThat(zip.getEntry("META-INF/container.xml")).isNotNull();
            assertThat(zip.getEntry("OEBPS/content.opf")).isNotNull();
            assertThat(zip.getEntry("OEBPS/text.xhtml")).isNotNull();
        }

        EpubExtractor.Doc doc = new EpubExtractor().extract(new FileSystemResource(stored));
        assertThat(doc.chapters()).isNotEmpty();
        assertThat(doc.chapters().stream().flatMap(chapter -> chapter.paragraphs().stream()).map(EpubExtractor.Para::text))
                .contains("First paragraph.", "Second paragraph with <unsafe> characters & punctuation.");
    }

    @Test
    void rejectsUnsupportedUploads() {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = new IngestionUploadStorage(properties, new PlainTextEpubConverter());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "book.pdf",
                "application/pdf",
                "not supported".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> storage.store(file, "Book", "en"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only EPUB and TXT files are supported");
    }
}
