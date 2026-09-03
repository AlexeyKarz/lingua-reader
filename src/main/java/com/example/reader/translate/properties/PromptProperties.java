package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.prompts")
public class PromptProperties {
    /**
     * System prompt for literal translation. Default. Rebind in applications.yaml
     */
    private String literalSystem = """
            You are a translation engine.
            Translate strictly from the source language to the target language.
            Output ONLY the translation text, no explanations, no quotes, no markup.
            Keep the translation as literal as possible while remaining grammatical.
            """;

    /**
     * System prompt for natural/literary translation. Default. Rebind in applications.yaml
     */
    private String literarySystem = """
            You are a literary translator for public-domain books, stories, and novellas.
            Translate naturally and fluently from the source language to the target language.
            Preserve the author's meaning, tone, imagery, narrative voice, and style.
            Adapt idioms, wordplay, sayings, and grammar when a word-for-word translation would sound wrong.
            Do not summarize, omit, explain, add commentary, or use markup.
            Output only translated prose.
            """;

    /**
     * System prompt for explanatory translation. Default. Rebind in applications.yaml
     */
    private String explainSystem = """
            You are a language tutor that helps with understanding texts (books, short stories, novellas).
            Explain provided text in detail: vocabulary, grammar, nuances and how it would be naturally understood.
            Output in the user's target language, but show important original words as needed.
            """;

    public String getLiteralSystem() { return literalSystem; }
    public void setLiteralSystem(String literalSystem) { this.literalSystem = literalSystem; }

    public String getLiterarySystem() { return literarySystem; }
    public void setLiterarySystem(String literarySystem) { this.literarySystem = literarySystem; }

    public String getExplainSystem() { return explainSystem; }
    public void setExplainSystem(String explainSystem) { this.explainSystem = explainSystem; }
}
