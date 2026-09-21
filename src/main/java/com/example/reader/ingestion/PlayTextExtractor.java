package com.example.reader.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

        List<Unit> units = new ArrayList<>();
        String speaker = null;
        int ord = 0;

        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
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
