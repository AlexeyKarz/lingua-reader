package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlayTextExtractorTest {
    private final PlayTextExtractor extractor = new PlayTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void keepsVerseLinesAndAttachesSpeakerCues() throws Exception {
        var doc = extractor.extract(new ClassPathResource("plays/mini_de.txt"));

        assertThat(doc.units()).extracting(PlayTextExtractor.Unit::text)
                .containsExactly(
                        "Der Abend fällt über die Stadt.",
                        "Hörst du die Glocken?",
                        "Ich höre sie.",
                        "Doch heute klingen sie anders.",
                        "Dann ist es Zeit zu gehen."
                );
        assertThat(doc.units()).extracting(PlayTextExtractor.Unit::speaker)
                .containsExactly("ANNA", "ANNA", "KARL", "KARL", "ANNA");
    }

    @Test
    void acceptsSpeakerCuesWithTrailingPunctuation() throws Exception {
        assertThat(extractor.isSpeakerCue("MEDEA.")).isTrue();
        assertThat(extractor.isSpeakerCue("This is spoken text.")).isFalse();
    }

    @Test
    void distinguishesRepeatedPlayCuesFromProseHeadings() {
        assertThat(extractor.looksLikePlay("TITLE\n\nCHAPTER ONE\n\nOrdinary prose.".getBytes())).isFalse();
        assertThat(extractor.looksLikePlay("ANNA\nHello.\nKARL\nHi.\nANNA\nGoodbye.".getBytes())).isTrue();
    }

    @Test
    void extractsSpeakerAwareUnitsFromEpub() throws Exception {
        Path epub = tempDir.resolve("play.epub");
        new PlainTextEpubConverter().convert(
                "ANNA\n\nGood evening.\n\nKARL\n\nGood night.\n\nANNA\n\nWait!".getBytes(StandardCharsets.UTF_8),
                epub,
                "Mini Play",
                "en"
        );

        var doc = extractor.extractEpub(new FileSystemResource(epub));

        assertThat(doc.units()).extracting(PlayTextExtractor.Unit::text)
                .contains("Good evening.", "Good night.", "Wait!");
        assertThat(doc.units().stream().filter(unit -> "Good night.".equals(unit.text())).findFirst().orElseThrow().speaker())
                .isEqualTo("KARL");
    }
}
