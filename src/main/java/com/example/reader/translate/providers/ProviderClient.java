package com.example.reader.translate.providers;

public interface ProviderClient {

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

    /**
     * Explanatory translation: grammar, nuance, etc.
     * Can include longer / structured output.
     */
    String explain(String text, String source, String target, String bookContext);
}
