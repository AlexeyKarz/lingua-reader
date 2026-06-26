package com.example.reader.translate.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "translate.limits")
public class TranslateLimitProperties {
    private boolean enabled = true;
    private int perIpDaily = 10;
    private int globalDaily = 100;
    private int maxTextChars = 800;

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
}
