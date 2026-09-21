package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PlayTextExtractorTest {
    private final PlayTextExtractor extractor = new PlayTextExtractor();

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
}
