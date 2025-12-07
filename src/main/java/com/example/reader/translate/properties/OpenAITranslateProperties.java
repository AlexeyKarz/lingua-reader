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

    /**
     * Whether this provider is enabled.
     */
    private boolean enabled = false;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
