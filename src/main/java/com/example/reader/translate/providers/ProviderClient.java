package com.example.reader.translate.providers;

public interface ProviderClient {

    default LiteraryModelCatalog literaryModels() {
        return new LiteraryModelCatalog(
                getClass().getSimpleName(),
                null,
                java.util.List.of(),
                false,
                "This translation provider does not publish a selectable model list."
        );
    }

    /**
     * Literal translation, as close as possible to the source wording.
     * Used both for single words and short sentences.
     */
    String literal(String text, String source, String target);

    /**
     * Natural/literary translation that preserves authorial tone and meaning
     * without forcing word-by-word structure.
     */
    String literary(String text, String source, String target, String bookContext);

    default String literary(String text, String source, String target, String bookContext, String modelOverride) {
        return literary(text, source, target, bookContext);
    }

    /**
     * Explanatory translation: grammar, nuance, etc.
     * Can include longer / structured output.
     */
    String explain(String text, String source, String target, String bookContext);
}
