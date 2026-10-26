package com.example.reader.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class PdfOcrService {
    private final boolean enabled;
    private final String executable;
    private final int dpi;
    private final Duration pageTimeout;

    public PdfOcrService(
            @Value("${mlr.ingestion.pdf.ocr.enabled:true}") boolean enabled,
            @Value("${mlr.ingestion.pdf.ocr.executable:tesseract}") String executable,
            @Value("${mlr.ingestion.pdf.ocr.dpi:200}") int dpi,
            @Value("${mlr.ingestion.pdf.ocr.page-timeout:120s}") Duration pageTimeout
    ) {
        this.enabled = enabled;
        this.executable = executable;
        this.dpi = dpi;
        this.pageTimeout = pageTimeout;
    }

    public List<String> extractPages(PDDocument document, String language) throws Exception {
        return extractPageData(document, language).stream().map(OcrPage::text).toList();
    }

    public List<OcrPage> extractPageData(PDDocument document, String language) throws Exception {
        if (!enabled) {
            throw new IllegalArgumentException("PDF contains only scanned images and PDF OCR is disabled");
        }
        PDFRenderer renderer = new PDFRenderer(document);
        List<OcrPage> pages = new ArrayList<>(document.getNumberOfPages());
        String ocrLanguage = tesseractLanguage(language);

        for (int page = 0; page < document.getNumberOfPages(); page++) {
            Path image = Files.createTempFile("mlr-pdf-ocr-", ".png");
            Path errors = Files.createTempFile("mlr-pdf-ocr-", ".log");
            try {
                ImageIO.write(renderer.renderImageWithDPI(page, dpi, ImageType.GRAY), "png", image.toFile());
                byte[] pageImagePng = Files.readAllBytes(image);
                Process process;
                try {
                    process = new ProcessBuilder(
                            executable, image.toString(), "stdout", "-l", ocrLanguage, "--psm", "3"
                    ).redirectError(errors.toFile()).start();
                } catch (Exception ex) {
                    throw new IllegalStateException(
                            "PDF OCR could not start; ensure the open-source Tesseract engine is installed", ex
                    );
                }

                CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
                if (!process.waitFor(pageTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("PDF OCR timed out on page " + (page + 1));
                }
                String text = output.join();
                if (process.exitValue() != 0) {
                    String detail = Files.readString(errors, StandardCharsets.UTF_8).strip();
                    throw new IllegalStateException("PDF OCR failed on page " + (page + 1)
                            + (detail.isBlank() ? "" : ": " + detail));
                }
                pages.add(new OcrPage(text, pageImagePng));
            } finally {
                Files.deleteIfExists(image);
                Files.deleteIfExists(errors);
            }
        }
        return pages;
    }

    public record OcrPage(String text, byte[] imagePng) {}

    private String tesseractLanguage(String language) {
        String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "el", "gr", "ell" -> "ell+eng";
            case "de", "deu" -> "deu+eng";
            case "es", "spa" -> "spa+eng";
            case "fr", "fra" -> "fra+eng";
            case "ru", "rus" -> "rus+eng";
            default -> "eng";
        };
    }
}
