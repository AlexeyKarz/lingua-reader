package com.example.reader.dictionary;

import java.util.List;

public record DictionaryLookupResult(
        boolean found,
        String formattedText,
        String lemma,
        String partOfSpeech,
        String gender,
        List<String> definitions,
        List<String> examples,
        List<String> translations,
        String sourceName,
        String sourceUrl
) {
    public static DictionaryLookupResult missing() {
        return new DictionaryLookupResult(false, "", "", "", "", List.of(), List.of(), List.of(), "", "");
    }
}
