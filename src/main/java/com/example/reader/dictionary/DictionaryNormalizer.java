package com.example.reader.dictionary;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DictionaryNormalizer {
    private static final Pattern EDGE_NON_WORD = Pattern.compile("^[^\\p{L}\\p{M}]+|[^\\p{L}\\p{M}]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DictionaryNormalizer() {
    }

    public static String normalizeWord(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = EDGE_NON_WORD.matcher(text.trim()).replaceAll("");
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        return normalized.toLowerCase(Locale.ROOT);
    }

    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String compact = WHITESPACE.matcher(text.trim()).replaceAll(" ");
        return Normalizer.normalize(compact, Normalizer.Form.NFC);
    }
}
