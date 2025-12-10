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

    /**
     * Base URL, default public endpoint.
     */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * Timeout in seconds.
     */
    private int timeoutSeconds = 30;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
