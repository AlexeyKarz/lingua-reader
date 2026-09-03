package com.example.reader.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateLiteraryEditionRequestTest {

    @Test
    void withDefaultsNormalizesFieldsAndBuildsJobRequest() {
        GenerateLiteraryEditionRequest normalized = new GenerateLiteraryEditionRequest(
                " A Book ",
                " DE ",
                " EN ",
                1915,
                " Publisher ",
                " ",
                null,
                null,
                null,
                null,
                null
        ).withDefaults();

        assertThat(normalized.workTitle()).isEqualTo("A Book");
        assertThat(normalized.sourceLang()).isEqualTo("de");
        assertThat(normalized.targetLang()).isEqualTo("en");
        assertThat(normalized.publisher()).isEqualTo("Publisher");
        assertThat(normalized.translator()).isEqualTo("AI literary translation");
        assertThat(normalized.bodyOnly()).isTrue();
        assertThat(normalized.replaceExisting()).isTrue();
        assertThat(normalized.maxSourceChars()).isEqualTo(120_000);
        assertThat(normalized.chunkMaxChars()).isEqualTo(1_800);
        assertThat(normalized.contextChars()).isEqualTo(600);

        IngestionRequest jobRequest = normalized.toJobRequest();
        assertThat(jobRequest.workTitle()).isEqualTo("A Book");
        assertThat(jobRequest.lang()).isEqualTo("en");
        assertThat(jobRequest.fileSpec()).isEqualTo("ai-literary://de/en/A Book");
        assertThat(jobRequest.year()).isEqualTo(1915);
        assertThat(jobRequest.publisher()).isEqualTo("Publisher");
        assertThat(jobRequest.translator()).isEqualTo("AI literary translation");
        assertThat(jobRequest.originalLang()).isEqualTo("de");
    }

    @Test
    void validateRejectsMissingAndUnsafeInputs() {
        assertThatThrownBy(() -> new GenerateLiteraryEditionRequest(
                "Book", "en", "en", null, null, null,
                null, null, 1, 1, 0
        ).withDefaults().validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceLang and targetLang must differ");

        assertThatThrownBy(() -> new GenerateLiteraryEditionRequest(
                "Book", "de", "en", null, null, null,
                null, null, 0, 1, 0
        ).withDefaults().validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSourceChars must be >= 1");
    }
}
