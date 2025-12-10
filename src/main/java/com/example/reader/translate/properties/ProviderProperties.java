package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate")
public class ProviderProperties {
    /**
     * Which provider to use: stub | openai | google | ollama (rebind in application.yaml)
     */
    private String provider = "stub"; // default

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
