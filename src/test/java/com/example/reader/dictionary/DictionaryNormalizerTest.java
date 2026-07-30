package com.example.reader.dictionary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryNormalizerTest {
    @Test
    void normalizesWordsWithoutDroppingLanguageCharacters() {
        assertThat(DictionaryNormalizer.normalizeWord("“Übermut,”")).isEqualTo("übermut");
        assertThat(DictionaryNormalizer.normalizeWord("Niño")).isEqualTo("niño");
        assertThat(DictionaryNormalizer.normalizeWord("Straße")).isEqualTo("straße");
    }

    @Test
    void normalizesCacheTextByCompactingWhitespace() {
        assertThat(DictionaryNormalizer.normalizeText("  One   morning\nGregor  "))
                .isEqualTo("One morning Gregor");
    }
}
