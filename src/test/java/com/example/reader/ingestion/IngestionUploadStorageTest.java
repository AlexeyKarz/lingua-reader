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
        IngestionUploadStorage storage = storage(properties);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../Unsafe Name.epub",
                "application/epub+zip",
                "epub-content".getBytes(StandardCharsets.UTF_8)
        );

        String fileSpec = storage.store(file, "Unsafe Book", "en", "prose");

        Path stored = Path.of(URI.create(fileSpec));
        assertThat(stored).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).endsWith("-Unsafe-Name.epub");
        assertThat(Files.readString(stored)).isEqualTo("epub-content");
    }

    @Test
    void storesPlainTextForDirectParsing() throws Exception {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = storage(properties);
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

        String fileSpec = storage.store(file, "Plain Book", "en", "prose");

        Path stored = Path.of(URI.create(fileSpec));
        assertThat(stored).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(stored.getFileName().toString()).endsWith("-plain-book.txt");
        EpubExtractor.Doc doc = new PlainTextExtractor().extract(new org.springframework.core.io.FileSystemResource(stored));
        assertThat(doc.chapters()).isNotEmpty();
        assertThat(doc.chapters().stream().flatMap(chapter -> chapter.paragraphs().stream()).map(EpubExtractor.Para::text))
                .contains("First paragraph.", "Second paragraph with <unsafe> characters & punctuation.");
    }

    @Test
    void keepsRomanNumeralsAsTxtChapterHeadings() throws Exception {
        Path text = tempDir.resolve("poem.txt");
        Files.writeString(text, """
                I
                First stanza line one.
                First stanza line two.

                II
                Second stanza.
                """);

        EpubExtractor.Doc doc = new PlainTextExtractor().extract(
                new org.springframework.core.io.FileSystemResource(text)
        );

        assertThat(doc.chapters()).extracting(EpubExtractor.Chapter::title).containsExactly("I", "II");
        assertThat(doc.chapters().getFirst().paragraphs()).extracting(EpubExtractor.Para::text)
                .containsExactly("First stanza line one. First stanza line two.");
        assertThat(doc.chapters().get(1).paragraphs()).extracting(EpubExtractor.Para::text)
                .containsExactly("Second stanza.");
    }

    @Test
    void splitsGreekTextWithFallbackSentenceSplitter() throws Exception {
        EpubExtractor extractor = new EpubExtractor();

        var sentences = extractor.splitSentences("el", "Πρώτη πρόταση. Τι γίνεται; Δεύτερη!");

        assertThat(sentences)
                .extracting(EpubExtractor.Sent::text)
                .containsExactly("Πρώτη πρόταση.", "Τι γίνεται;", "Δεύτερη!");
    }

    @Test
    void rejectsUnsupportedUploads() {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = storage(properties);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "book.pdf",
                "application/pdf",
                "not supported".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> storage.store(file, "Book", "en", "prose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only EPUB and TXT files are supported");
    }

    @Test
    void preservesDetectedPlayTextForSpeakerAwareIngestion() throws Exception {
        IngestionUploadProperties properties = new IngestionUploadProperties();
        properties.setDir(tempDir);
        IngestionUploadStorage storage = storage(properties);
        MockMultipartFile file = new MockMultipartFile(
                "file", "play.txt", "text/plain",
                "ANNA\nFirst line.\n\nKARL\nSecond line.\n\nANNA\nThird line.".getBytes(StandardCharsets.UTF_8)
        );

        Path stored = Path.of(URI.create(storage.store(file, "Play", "en", "play")));

        assertThat(stored.getFileName().toString()).endsWith("-play.txt");
        assertThat(Files.readString(stored)).contains("ANNA", "KARL");
    }

    private IngestionUploadStorage storage(IngestionUploadProperties properties) {
        return new IngestionUploadStorage(properties);
    }
}
