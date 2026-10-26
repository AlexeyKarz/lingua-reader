package com.example.reader.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfExtractor {
    private final PlayTextExtractor playTextExtractor;
    private final PdfOcrService ocrService;
    private final OcrCleanupService cleanupService;

    public PdfExtractor(PlayTextExtractor playTextExtractor, PdfOcrService ocrService,
                        OcrCleanupService cleanupService) {
        this.playTextExtractor = playTextExtractor;
        this.ocrService = ocrService;
        this.cleanupService = cleanupService;
    }

    public EpubExtractor.Doc extract(Resource pdf) throws Exception {
        return extract(pdf, "en");
    }

    public EpubExtractor.Doc extract(Resource pdf, String language) throws Exception {
        return extract(pdf, language, false, null);
    }

    public EpubExtractor.Doc extract(Resource pdf, String language, boolean aiCleanup, String cleanupModel) throws Exception {
        List<EpubExtractor.Chapter> chapters = new ArrayList<>();
        List<String> pages = extractPages(pdf, language, aiCleanup, cleanupModel);
        for (int index = 0; index < pages.size(); index++) {
            String pageText = pages.get(index);
            if (pageText.isBlank()) continue;
            EpubExtractor.Chapter chapter = new EpubExtractor.Chapter(
                    "Page " + (index + 1), null, new ArrayList<>()
            );
            for (String block : pageText.split("(?:\\R\\s*){2,}")) {
                // A PDF stores visual lines rather than semantic paragraphs. Join
                // wrapped lines while retaining blank-line paragraph boundaries.
                String text = block.strip().replaceAll("(?<=\\p{L})-\\R(?=\\p{L})", "")
                        .replaceAll("\\s+", " ");
                if (!text.isBlank()) chapter.paragraphs().add(new EpubExtractor.Para(text, null));
            }
            if (!chapter.paragraphs().isEmpty()) chapters.add(chapter);
        }
        ensureReadable(chapters);
        return new EpubExtractor.Doc(chapters);
    }

    public PlayTextExtractor.Doc extractPlay(Resource pdf, String language, boolean aiCleanup, String cleanupModel) throws Exception {
        String text = String.join("\n\n", extractPages(pdf, language, aiCleanup, cleanupModel));
        if (text.isBlank()) ensureReadable(List.of());
        return playTextExtractor.extract(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
    }

    public PlayTextExtractor.Doc extractPlay(Resource pdf, String language) throws Exception {
        return extractPlay(pdf, language, false, null);
    }

    private List<String> extractPages(Resource pdf, String language, boolean aiCleanup, String cleanupModel) throws Exception {
        try (var input = pdf.getInputStream(); PDDocument document = PDDocument.load(input)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            if (pages.stream().allMatch(String::isBlank)) {
                if (aiCleanup) {
                    pages = cleanupService.cleanOcrPages(
                            ocrService.extractPageData(document, language), pdf, language, cleanupModel
                    );
                } else {
                    pages = ocrService.extractPages(document, language);
                }
            }
            return pages;
        }
    }

    private void ensureReadable(List<EpubExtractor.Chapter> chapters) {
        boolean readable = chapters.stream().anyMatch(chapter -> !chapter.paragraphs().isEmpty());
        if (!readable) {
            throw new IllegalArgumentException(
                    "PDF does not contain extractable text; it may be scanned and require OCR"
            );
        }
    }
}
