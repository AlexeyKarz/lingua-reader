package com.example.reader.dictionary;

import java.util.List;

public record DictionaryEntry(
        long id,
        String sourceLang,
        String targetLang,
        String surface,
        String normalized,
        String lemma,
        String partOfSpeech,
        String gender,
        List<String> definitions,
        List<String> examples,
        List<String> translations,
        String sourceName,
        String sourceUrl
) {
}
