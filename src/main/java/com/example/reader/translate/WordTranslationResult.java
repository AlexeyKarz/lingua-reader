package com.example.reader.translate;

import com.example.reader.dictionary.DictionaryEntry;

import java.util.List;

public record WordTranslationResult(
        String text,
        String source,
        String target,
        String translated,
        boolean dictionary,
        boolean aiGenerated,
        String lemma,
        String partOfSpeech,
        String gender,
        String baseSurface,
        String baseNormalized,
        List<String> definitions,
        List<String> examples,
        List<String> translations,
        List<String> formTags,
        List<DictionaryEntry> entries,
        String sourceName,
        String sourceUrl
) {
}
