package com.example.reader.ingestion;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class EpubExtractor {

    public record Para(String text, String cfi) {}
    public record Chapter(String title, String cfi, List<Para> paragraphs) {}
    public record Doc(List<Chapter> chapters) {}

    public Doc extract(Resource epub) throws Exception {
        var parser = new AutoDetectParser();
        var handler = new ToXMLContentHandler();
        var md = new Metadata();

        try (InputStream is = epub.getInputStream()) {
            parser.parse(is, handler, md);
        }

        String xhtml = handler.toString();
        Document js = Jsoup.parse(xhtml, StandardCharsets.UTF_8.name());

        List<Chapter> chapters = new ArrayList<>();
        Chapter current = new Chapter("Chapter 1", null, new ArrayList<>());
        chapters.add(current);

        // Walk ALL headings and paragraphs in document order (not just direct children)
        for (Element el : js.select("body h1, body h2, body h3, body p")) {
            String tag = el.tagName().toLowerCase();
            if (tag.matches("h[1-3]")) {
                // start a new chapter on each heading
                current = new Chapter(el.text(), null, new ArrayList<>());
                chapters.add(current);
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


    // Sentence split per paragraph with OpenNLP
    public record Sent(int ord, String text, String cfi) {}

    public List<Sent> splitSentences(String lang, String paragraph) throws Exception {
        var modelName = switch (lang) {
            case "de" -> "models/de-sent.bin";
            case "en" -> "models/en-sent.bin";
            case "ru" -> "models/ru-sent.bin";
            default   -> "models/en-sent.bin";
        };
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
}
