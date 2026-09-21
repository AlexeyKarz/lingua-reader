package com.example.reader.ingestion;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class IngestCommand implements CommandLineRunner {
    private final IngestionFacade ingestionFacade;

    public IngestCommand(IngestionFacade ingestionFacade) {
        this.ingestionFacade = ingestionFacade;
    }

    @Override public void run(String... args) throws Exception {
        if (args.length == 0 || !"ingest".equals(args[0])) return;

        Map<String,String> flags = parseFlags(Arrays.copyOfRange(args, 1, args.length));
        String work = flags.get("--work");
        String lang = flags.get("--lang");
        String file = flags.get("--file");
        Integer year = flags.containsKey("--year") ? Integer.valueOf(flags.get("--year")) : null;

        IngestionRequest request = new IngestionRequest(
                work,
                lang,
                file,
                year,
                flags.getOrDefault("--publisher", null),
                flags.getOrDefault("--translator", null),
                flags.getOrDefault("--author", null),
                flags.getOrDefault("--description", null),
                flags.getOrDefault("--original-lang", null),
                flags.getOrDefault("--content-type", "prose")
        ).withDefaults();

        IngestionResult result = ingestionFacade.ingest(request, null);
        System.out.println(
                "Ingested " + request.workTitle() + " [" + request.lang() + "] from " + request.fileSpec()
                        + " (workId=" + result.workId()
                        + ", editionId=" + result.editionId()
                        + ", chapters=" + result.chapters()
                        + ", paragraphs=" + result.paragraphs()
                        + ", sentences=" + result.sentences() + ")"
        );

        // If running headless (non-web), exit after ingest; otherwise leave the server running.
        if (isHeadless(flags)) {
            System.exit(0);
        }
    }

    private Map<String,String> parseFlags(String[] arr) {
        Map<String,String> m = new HashMap<>();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].startsWith("--")) {
                String key = arr[i];
                String v = "true";
                if (i + 1 < arr.length && !arr[i + 1].startsWith("--")) {
                    v = arr[i + 1];
                    i++; // skip value
                }
                m.put(key, v);
            }
        }
        return m;
    }

    private boolean isHeadless(Map<String,String> flags) {
        // Allow explicit override via flag
        if ("true".equalsIgnoreCase(flags.getOrDefault("--headless", "false"))) return true;
        // Also check Spring profile hint, if provided
        String appType = System.getProperty("spring.main.web-application-type");
        return appType != null && appType.equalsIgnoreCase("none");
    }
}
