package com.example.reader.ingestion;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EpubExtractor {

    private static final Logger log = LoggerFactory.getLogger(EpubExtractor.class);
    private static final Pattern FALLBACK_SENTENCE_PATTERN =
            Pattern.compile("[^.!?;\\u037E\\u2026]+(?:[.!?;\\u037E\\u2026]+|$)");

    public record Para(String text, String cfi) {}
    public record Chapter(String title, String cfi, List<Para> paragraphs) {}
    public record Doc(List<Chapter> chapters) {}

    public Doc extract(Resource epub) throws Exception {
        var parser = new AutoDetectParser();
        var handler = new ToXMLContentHandler();
        var md = new Metadata();

        try (InputStream is = epub.getInputStream()) {
            parser.parse(is, handler, md);
        } catch (Exception e) {
            log.error("Failed to parse EPUB {}", epub.getFilename(), e);
            throw e;
        }

        String xhtml = handler.toString();
        if (xhtml == null || xhtml.isBlank()) {
            log.error("Parsed EPUB {} but received empty content from Tika", epub.getFilename());
            throw new IllegalStateException("EPUB parse produced empty content");
        }

        Document js = Jsoup.parse(xhtml, StandardCharsets.UTF_8.name());

        List<Chapter> chapters = new ArrayList<>();
        Chapter current = new Chapter("Chapter 1", null, new ArrayList<>());
        chapters.add(current);

        // Walk ALL headings and paragraphs in document order (not just direct children)
        for (Element el : js.select("body h1, body h2, body h3, body p")) {
            String tag = el.tagName().toLowerCase();
            if (tag.matches("h[1-3]")) {
                // start a new chapter only on likely chapter headings
                String heading = el.text().trim();
                if (isLikelyChapterHeading(heading)) {
                    current = new Chapter(heading, null, new ArrayList<>());
                    chapters.add(current);
                }
            } else if (tag.equals("p")) {
                String t = el.text().trim();
                if (!t.isEmpty()) {
                    current.paragraphs().add(new Para(t, null));
                }
            }
        }

        // Remove dummy first chapter if it ended up empty
        if (!chapters.isEmpty()
                && chapters.getFirst().paragraphs().isEmpty()
                && chapters.getFirst().title().equals("Chapter 1")) {
            chapters.removeFirst();
        }

        return new Doc(chapters);
    }

    // Heuristic: avoid counting every heading as a chapter; focus on likely chapter labels.
    private boolean isLikelyChapterHeading(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty()) return false;

        String lower = s.toLowerCase(Locale.ROOT);
        String plain = stripAccents(lower);
        // Ignore common non-chapter headings
        if (plain.contains("contents")
                || plain.contains("illustration")
                || plain.contains("copyright")
                || plain.contains("license")
                || plain.contains("project gutenberg")
                || plain.contains("title page")
                || plain.contains("preface")
                || plain.contains("prefacio")
                || plain.contains("prologo")
                || plain.contains("prologue")) {
            return false;
        }

        // If it explicitly mentions chapter/stave/part/book/section, assume chapter
        if (lower.matches(".*\\b(chapter|stave|part|book|section)\\b.*")) {
            return true;
        }
        if (plain.matches(".*\\b(chapitre|estrofa|capitulo|kapitel|capitolo|capitulo|capitulos|parte|libro|seccion|sezione|teil|cuento)\\b.*")) {
            return true;
        }

        // Leading roman numerals or digits are often chapter labels
        if (lower.matches("^(chapter\\s+)?[ivxlcdm]+\\b.*")) return true;
        if (lower.matches("^\\d+\\b.*")) return true;

        // Otherwise treat short headings as non-chapters
        return false;
    }

    private String stripAccents(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }


    // Sentence split per paragraph with OpenNLP
    public record Sent(int ord, String text, String cfi) {}

    public List<Sent> splitSentences(String lang, String paragraph) throws Exception {
        String normalizedLang = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        var modelName = switch (normalizedLang) {
            case "de" -> "models/de-sent.bin";
            case "en" -> "models/en-sent.bin";
            case "ru" -> "models/ru-sent.bin";
            case "es" -> "models/es-sent.bin";
            case "fr" -> "models/fr-sent.bin";
            default   -> null;
        };
        if (modelName == null) {
            return splitSentencesByPunctuation(paragraph);
        }
        try (var in = getClass().getClassLoader().getResourceAsStream(modelName)) {
            if (in == null) throw new IllegalStateException("Sentence model not found: " + modelName);
            var model = new opennlp.tools.sentdetect.SentenceModel(in);
            var sd = new opennlp.tools.sentdetect.SentenceDetectorME(model);
            String[] sents = sd.sentDetect(paragraph);
            List<Sent> out = new ArrayList<>(sents.length);
            for (int i=0;i<sents.length;i++) out.add(new Sent(i+1, sents[i].trim(), null));
            return out;
        }
    }

    private List<Sent> splitSentencesByPunctuation(String paragraph) {
        String cleaned = paragraph == null ? "" : paragraph.trim();
        if (cleaned.isBlank()) {
            return List.of();
        }

        Matcher matcher = FALLBACK_SENTENCE_PATTERN.matcher(cleaned);
        List<Sent> out = new ArrayList<>();
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isBlank()) {
                out.add(new Sent(out.size() + 1, sentence, null));
            }
        }
        if (out.isEmpty()) {
            out.add(new Sent(1, cleaned, null));
        }
        return out;
    }
}
