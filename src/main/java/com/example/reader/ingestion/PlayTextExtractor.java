package com.example.reader.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Parses a plain-text play without treating speaker cues as spoken text. */
@Component
public class PlayTextExtractor {

    public record Unit(int ord, String text, String speaker) {}
    public record Doc(List<Unit> units) {}

    public Doc extract(Resource textResource) throws Exception {
        String text;
        try (var in = textResource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        return extractLines(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n")));
    }

    public Doc extractEpub(Resource epubResource) throws Exception {
        var handler = new ToXMLContentHandler();
        try (var in = epubResource.getInputStream()) {
            new AutoDetectParser().parse(in, handler, new Metadata());
        }
        var document = Jsoup.parse(handler.toString(), StandardCharsets.UTF_8.name());
        List<String> lines = new ArrayList<>();
        document.select("body h1, body h2, body h3, body p").forEach(element -> {
            String block = element.wholeText().strip();
            if (!block.isEmpty()) {
                lines.addAll(List.of(block.replace("\r\n", "\n").replace('\r', '\n').split("\n")));
            }
        });
        return extractLines(lines);
    }

    private Doc extractLines(List<String> lines) {
        List<Unit> units = new ArrayList<>();
        String speaker = null;
        int ord = 0;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) continue;

            if (isSpeakerCue(line)) {
                speaker = normalizeSpeaker(line);
                continue;
            }

            // Keep verse lines intact. Alignment works on these semantic units instead
            // of asking a prose sentence detector to merge or split poetry.
            units.add(new Unit(++ord, line, speaker));
        }
        if (units.isEmpty()) {
            throw new IllegalStateException("Plain-text play contains no readable lines");
        }
        return new Doc(units);
    }

    public boolean looksLikePlay(byte[] textBytes) {
        String text = new String(textBytes, StandardCharsets.UTF_8);
        Map<String, Integer> cues = new HashMap<>();
        int cueCount = 0;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.strip();
            if (!line.isEmpty() && isSpeakerCue(line)) {
                cueCount++;
                cues.merge(normalizeSpeaker(line), 1, Integer::sum);
            }
        }
        // Repetition avoids classifying a prose document with a few uppercase
        // headings as drama while recognizing cast lists and recurring speakers.
        return cueCount >= 3 && cues.values().stream().anyMatch(count -> count >= 2);
    }

    boolean isSpeakerCue(String value) {
        String candidate = normalizeSpeaker(value);
        if (candidate.isBlank() || candidate.length() > 60 || candidate.split("\\s+").length > 6) {
            return false;
        }
        boolean hasLetter = candidate.codePoints().anyMatch(Character::isLetter);
        if (!hasLetter) return false;

        String letters = candidate.replaceAll("[^\\p{L}]", "");
        return !letters.isEmpty() && letters.equals(letters.toUpperCase(Locale.ROOT));
    }

    private String normalizeSpeaker(String value) {
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        return normalized.replaceFirst("[.:：]+$", "").strip();
    }
}
