package com.example.reader.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfExtractorTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsTextAndPreservesPdfPages() throws Exception {
        Path pdf = tempDir.resolve("book.pdf");
        try (PDDocument document = new PDDocument()) {
            addPage(document, "First page sentence.");
            addPage(document, "Second page sentence.");
            document.save(pdf.toFile());
        }

        EpubExtractor.Doc result = extractor().extract(new FileSystemResource(pdf));

        assertThat(result.chapters()).extracting(EpubExtractor.Chapter::title)
                .containsExactly("Page 1", "Page 2");
        assertThat(result.chapters().stream()
                .flatMap(chapter -> chapter.paragraphs().stream())
                .map(EpubExtractor.Para::text))
                .contains("First page sentence.", "Second page sentence.");
    }

    @Test
    void explainsThatImageOnlyPdfsNeedOcr() throws Exception {
        Path pdf = tempDir.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        PdfOcrService ocr = mock(PdfOcrService.class);
        when(ocr.extractPages(any(PDDocument.class), eq("en"))).thenReturn(List.of(""));

        assertThatThrownBy(() -> extractor(ocr).extract(new FileSystemResource(pdf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require OCR");
    }

    @Test
    void usesLanguageAwareOcrForImageOnlyPdf() throws Exception {
        Path pdf = tempDir.resolve("greek-scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        PdfOcrService ocr = mock(PdfOcrService.class);
        when(ocr.extractPages(any(PDDocument.class), eq("el")))
                .thenReturn(List.of("Αναγνωρισμένο ελληνικό κείμενο."));

        EpubExtractor.Doc result = extractor(ocr).extract(new FileSystemResource(pdf), "el");

        assertThat(result.chapters().getFirst().paragraphs())
                .extracting(EpubExtractor.Para::text)
                .containsExactly("Αναγνωρισμένο ελληνικό κείμενο.");
        verify(ocr).extractPages(any(PDDocument.class), eq("el"));
    }

    @Test
    void usesPageImagesForOptionalAiCleanup() throws Exception {
        Path pdf = tempDir.resolve("greek-vision-scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        byte[] image = new byte[]{1, 2, 3};
        PdfOcrService ocr = mock(PdfOcrService.class);
        when(ocr.extractPageData(any(PDDocument.class), eq("el")))
                .thenReturn(List.of(new PdfOcrService.OcrPage("κρεθάτι", image)));
        var provider = mock(com.example.reader.translate.providers.ProviderClient.class);
        when(provider.cleanOcr("κρεθάτι", image, "el", "vision-model"))
                .thenReturn("κρεβάτι");
        PdfExtractor extractor = new PdfExtractor(
                new PlayTextExtractor(), ocr, new OcrCleanupService(provider)
        );

        EpubExtractor.Doc result = extractor.extract(
                new FileSystemResource(pdf), "el", true, "vision-model"
        );

        assertThat(result.chapters().getFirst().paragraphs())
                .extracting(EpubExtractor.Para::text)
                .containsExactly("κρεβάτι");
        verify(ocr).extractPageData(any(PDDocument.class), eq("el"));
        verify(provider).cleanOcr("κρεθάτι", image, "el", "vision-model");
    }

    private PdfExtractor extractor() {
        return extractor(mock(PdfOcrService.class));
    }

    private PdfExtractor extractor(PdfOcrService ocr) {
        return new PdfExtractor(
                new PlayTextExtractor(), ocr,
                new OcrCleanupService(mock(com.example.reader.translate.providers.ProviderClient.class))
        );
    }

    private void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
