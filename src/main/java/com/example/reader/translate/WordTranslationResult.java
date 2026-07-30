package com.example.reader.translate;

import java.util.List;

public record WordTranslationResult(
        String text,
        String source,
        String target,
        String translated,
        boolean dictionary,
        String lemma,
        String partOfSpeech,
        String gender,
        List<String> definitions,
        List<String> examples,
        List<String> translations,
        String sourceName,
        String sourceUrl
) {
    public static WordTranslationResult provider(String text, String source, String target, String translated) {
        return new WordTranslationResult(
                text, source, target, translated, false, "", "", "", List.of(), List.of(), List.of(), "", ""
        );
    }
}
