package com.example.reader.translate.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StubProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(StubProviderClient.class);

    @Override
    public String literal(String text, String source, String target) {
        log.info("Stub literal translation {} -> {} ({} chars)", source, target, text.length());
        return "[" + source + "→" + target + "] " + text;
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        log.info("Stub explain translation {} -> {} ({} chars, ctx {} chars)",
                source, target, text.length(), bookContext == null ? 0 : bookContext.length());
        return "Explanation stub: " + text +
                " (from " + source + " to " + target + "). " +
                "Context: " + bookContext;
    }
}
