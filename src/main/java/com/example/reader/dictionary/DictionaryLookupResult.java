package com.example.reader.dictionary;

import java.util.List;

public record DictionaryLookupResult(
        boolean found,
        String formattedText,
        String lemma,
        String partOfSpeech,
        String gender,
        String baseSurface,
        String baseNormalized,
        List<String> definitions,
        List<String> examples,
        List<String> translations,
        List<DictionaryEntry> entries,
        String sourceName,
        String sourceUrl
) {
    public static DictionaryLookupResult missing() {
        return new DictionaryLookupResult(false, "", "", "", "", "", "", List.of(), List.of(), List.of(), List.of(), "", "");
    }
}
