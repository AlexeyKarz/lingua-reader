// src/main/java/com/example/reader/translate/OpenAITranslateProperties.java
package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.openai")
public class OpenAITranslateProperties {

    /**
     * API key, will be injected via environment variable.
     */
    private String apiKey;

    /**
     * Model name
     */
    private String model;
    private String literaryModel;
    private String ocrCleanupModel = "gpt-5.6-luna";

    /**
     * Timeout in seconds.
     */
    private int timeoutSeconds = 30;

    /**
     * Maximum output tokens per OpenAI response.
     */
    private int maxCompletionTokens = 300;
    private int literaryMaxCompletionTokens = 700;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLiteraryModel() { return literaryModel; }
    public void setLiteraryModel(String literaryModel) { this.literaryModel = literaryModel; }
    public String getOcrCleanupModel() { return ocrCleanupModel; }
    public void setOcrCleanupModel(String ocrCleanupModel) { this.ocrCleanupModel = ocrCleanupModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }

    public int getLiteraryMaxCompletionTokens() { return literaryMaxCompletionTokens; }
    public void setLiteraryMaxCompletionTokens(int literaryMaxCompletionTokens) {
        this.literaryMaxCompletionTokens = literaryMaxCompletionTokens;
    }
}
