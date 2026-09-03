package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.google")
public class GoogleTranslateProperties {
    /**
     * API key for Gemini
     */
    private String apiKey;

    /**
     * Model name, e.g. gemini-1.5-flash
     */
    private String model = "gemini-1.5-flash-latest";
    private String literaryModel;

    /**
     * Base URL, default public endpoint.
     */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * Timeout in seconds.
     */
    private int timeoutSeconds = 30;
    private int maxOutputTokens = 512;
    private int literaryMaxOutputTokens = 1024;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLiteraryModel() { return literaryModel; }
    public void setLiteraryModel(String literaryModel) { this.literaryModel = literaryModel; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public int getLiteraryMaxOutputTokens() { return literaryMaxOutputTokens; }
    public void setLiteraryMaxOutputTokens(int literaryMaxOutputTokens) {
        this.literaryMaxOutputTokens = literaryMaxOutputTokens;
    }
}
