package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.ollama")
public class OllamaTranslateProperties {

    private String baseUrl = "http://localhost:11434";
    private String model = "llama3.1:8b";
    private String literaryModel;
    private String explainModel; // optional override

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLiteraryModel() { return literaryModel; }
    public void setLiteraryModel(String literaryModel) { this.literaryModel = literaryModel; }

    public String getExplainModel() { return explainModel; }
    public void setExplainModel(String explainModel) { this.explainModel = explainModel; }
}
