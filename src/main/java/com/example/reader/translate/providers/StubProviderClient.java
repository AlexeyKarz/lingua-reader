package com.example.reader.translate.providers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

public class StubProviderClient implements ProviderClient {

    @Override
    public String literal(String text, String source, String target) {
        // super cheap, safe placeholder
        return "[" + source + "→" + target + "] " + text;
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        return "Explanation stub: " + text +
                " (from " + source + " to " + target + "). " +
                "Context: " + bookContext;
    }
}
