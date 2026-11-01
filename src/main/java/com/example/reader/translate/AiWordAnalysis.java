package com.example.reader.translate;

import java.util.List;

public record AiWordAnalysis(
        String selectedForm,
        String lemma,
        String baseForm,
        String partOfSpeech,
        String gender,
        List<String> grammaticalFeatures,
        List<String> translations,
        String usageNote
) {
    public static AiWordAnalysis translationOnly(String selectedForm, String translation) {
        return new AiWordAnalysis(
                selectedForm,
                selectedForm,
                selectedForm,
                "",
                "",
                List.of(),
                translation == null || translation.isBlank() ? List.of() : List.of(translation.trim()),
                ""
        );
    }
}
