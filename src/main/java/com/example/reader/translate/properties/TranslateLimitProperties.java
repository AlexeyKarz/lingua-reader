package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.limits")
public class TranslateLimitProperties {
    private boolean enabled = true;
    private int perIpDaily = 10;
    private int globalDaily = 100;
    private int maxTextChars = 800;
    private int literaryPerIpDaily = 5;
    private int literaryGlobalDaily = 50;
    private int literaryMaxTextChars = 2000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPerIpDaily() {
        return perIpDaily;
    }

    public void setPerIpDaily(int perIpDaily) {
        this.perIpDaily = perIpDaily;
    }

    public int getGlobalDaily() {
        return globalDaily;
    }

    public void setGlobalDaily(int globalDaily) {
        this.globalDaily = globalDaily;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getLiteraryPerIpDaily() {
        return literaryPerIpDaily;
    }

    public void setLiteraryPerIpDaily(int literaryPerIpDaily) {
        this.literaryPerIpDaily = literaryPerIpDaily;
    }

    public int getLiteraryGlobalDaily() {
        return literaryGlobalDaily;
    }

    public void setLiteraryGlobalDaily(int literaryGlobalDaily) {
        this.literaryGlobalDaily = literaryGlobalDaily;
    }

    public int getLiteraryMaxTextChars() {
        return literaryMaxTextChars;
    }

    public void setLiteraryMaxTextChars(int literaryMaxTextChars) {
        this.literaryMaxTextChars = literaryMaxTextChars;
    }

    public int perIpDailyFor(String action) {
        return "literary".equalsIgnoreCase(action) ? literaryPerIpDaily : perIpDaily;
    }

    public int globalDailyFor(String action) {
        return "literary".equalsIgnoreCase(action) ? literaryGlobalDaily : globalDaily;
    }

    public int maxTextCharsFor(String action) {
        return "literary".equalsIgnoreCase(action) ? literaryMaxTextChars : maxTextChars;
    }
}
