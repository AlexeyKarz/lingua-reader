package com.example.reader.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses TXT directly so structural headings are not flattened by an EPUB conversion round trip. */
@Component
public class PlainTextExtractor {

    public EpubExtractor.Doc extract(Resource resource) throws Exception {
        String text;
        try (var input = resource.getInputStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);

        List<EpubExtractor.Chapter> chapters = new ArrayList<>();
        EpubExtractor.Chapter current = new EpubExtractor.Chapter("Chapter 1", null, new ArrayList<>());
        chapters.add(current);
        List<String> paragraphLines = new ArrayList<>();

        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = normalizeLine(rawLine);
            if (line.isEmpty()) {
                flushParagraph(current, paragraphLines);
            } else if (isStructuralHeading(line)) {
                flushParagraph(current, paragraphLines);
                current = new EpubExtractor.Chapter(line, null, new ArrayList<>());
                chapters.add(current);
            } else {
                paragraphLines.add(line);
            }
        }
        flushParagraph(current, paragraphLines);

        if (!chapters.isEmpty() && chapters.getFirst().paragraphs().isEmpty()
                && "Chapter 1".equals(chapters.getFirst().title())) {
            chapters.removeFirst();
        }
        if (chapters.isEmpty() || chapters.stream().allMatch(chapter -> chapter.paragraphs().isEmpty())) {
            throw new IllegalArgumentException("TXT file does not contain readable text");
        }
        return new EpubExtractor.Doc(chapters);
    }

    private String normalizeLine(String line) {
        return line.strip().replaceAll("[ \\t\\f\\x0B]+", " ");
    }

    private void flushParagraph(EpubExtractor.Chapter chapter, List<String> lines) {
        if (lines.isEmpty()) return;
        chapter.paragraphs().add(new EpubExtractor.Para(String.join(" ", lines), null));
        lines.clear();
    }

    private boolean isStructuralHeading(String block) {
        if (block.length() > 90) return false;
        String lower = block.toLowerCase(Locale.ROOT);
        return lower.matches("^(chapter|stave|part|book|section)\\b.*")
                || lower.matches("^(chapitre|capitulo|capítulo|kapitel|parte|libro|teil)\\b.*")
                || lower.matches("^[ivxlcdm]+\\.?$")
                || lower.matches("^\\d+\\.?$");
    }
}
