package com.example.reader.ingestion;

import com.example.reader.translate.providers.ProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrCleanupServiceTest {
    @Test
    void appliesConservativeProviderCorrection() {
        ProviderClient provider = mock(ProviderClient.class);
        when(provider.cleanOcr("Ὁ Γκάνταλφ πῆγε oth πόρτα.", "el", "small-model"))
                .thenReturn("Ὁ Γκάνταλφ πῆγε στὴ πόρτα.");
        OcrCleanupService service = new OcrCleanupService(provider);

        List<String> result = service.clean(
                List.of("Ὁ Γκάνταλφ πῆγε oth πόρτα."), resource(), "el", "small-model"
        );

        assertThat(result).containsExactly("Ὁ Γκάνταλφ πῆγε στὴ πόρτα.");
    }

    @Test
    void rejectsProviderRewriteThatChangesTooMuch() {
        ProviderClient provider = mock(ProviderClient.class);
        when(provider.cleanOcr("Original literary sentence with a small OCR err0r.", "en", null))
                .thenReturn("A completely different modern summary invented by the model.");
        OcrCleanupService service = new OcrCleanupService(provider);

        List<String> result = service.clean(
                List.of("Original literary sentence with a small OCR err0r."), resource(), "en", null
        );

        assertThat(result).containsExactly("Original literary sentence with a small OCR err0r.");
    }

    @Test
    void suppliesRenderedPageImageToCleanupProvider() {
        ProviderClient provider = mock(ProviderClient.class);
        byte[] image = new byte[]{1, 2, 3};
        when(provider.cleanOcr("OCR texf", image, "en", "vision-model"))
                .thenReturn("OCR text");
        OcrCleanupService service = new OcrCleanupService(provider);

        List<String> result = service.cleanOcrPages(
                List.of(new PdfOcrService.OcrPage("OCR texf", image)),
                resource(), "en", "vision-model"
        );

        assertThat(result).containsExactly("OCR text");
        verify(provider).cleanOcr("OCR texf", image, "en", "vision-model");
    }

    private ByteArrayResource resource() {
        return new ByteArrayResource("pdf".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "scan.pdf"; }
        };
    }
}
