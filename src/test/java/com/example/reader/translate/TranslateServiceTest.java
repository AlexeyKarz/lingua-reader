package com.example.reader.translate;

import com.example.reader.dictionary.DictionaryService;
import com.example.reader.dictionary.DictionaryEntry;
import com.example.reader.dictionary.DictionaryLookupResult;
import com.example.reader.translate.providers.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateServiceTest {

    @Test
    void wordAddsProviderTranslationWhenDictionaryOnlyContainsAFormNote() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());
        DictionaryEntry form = new DictionaryEntry(
                1L, "key", "el", "ru", "ταξείδι", "ταξείδι", "", "noun", "",
                "ταξίδι", "ταξίδι", List.of("form-of"), List.of("παρωχημένη γραφή του ταξίδι"),
                List.of(), List.of(), "Kaikki", "https://kaikki.org/"
        );
        when(dictionaryService.lookup("ταξείδι", "el", "ru")).thenReturn(new DictionaryLookupResult(
                true, "ταξείδι\nForm of: ταξίδι", "", "noun", "", "ταξίδι", "ταξίδι",
                form.definitions(), List.of(), List.of(), List.of(form), "Kaikki", "https://kaikki.org/"
        ));
        when(cache.find(eq("word_analysis_v1"), eq("el"), eq("ru"), anyString(), anyString())).thenReturn(Optional.empty());
        when(provider.wordAnalysis("ταξείδι", "el", "ru", "")).thenReturn("""
                {"selectedForm":"ταξείδι","lemma":"ταξίδι","baseForm":"το ταξίδι","partOfSpeech":"noun",
                 "gender":"neuter","grammaticalFeatures":["obsolete spelling"],"translations":["путешествие"],
                 "usageNote":""}
                """);

        WordTranslationResult result = service.word("ταξείδι", "el", "ru");

        assertThat(result.dictionary()).isTrue();
        assertThat(result.aiGenerated()).isTrue();
        assertThat(result.baseSurface()).isEqualTo("το ταξίδι");
        assertThat(result.translations()).containsExactly("путешествие");
        assertThat(result.entries()).containsExactly(form);
    }

    @Test
    void missingDictionaryUsesSentenceContextForStructuredWordAnalysis() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());
        String context = "Selected sentence: Sie gingen mit den Kindern nach Hause.";

        when(dictionaryService.lookup("Kindern", "de", "en")).thenReturn(DictionaryLookupResult.missing());
        when(cache.find(eq("word_analysis_v1"), eq("de"), eq("en"), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(provider.wordAnalysis("Kindern", "de", "en", context)).thenReturn("""
                {
                  "selectedForm": "Kindern",
                  "lemma": "Kind",
                  "baseForm": "das Kind",
                  "partOfSpeech": "noun",
                  "gender": "neuter",
                  "grammaticalFeatures": ["dative", "plural"],
                  "translations": ["children"],
                  "usageNote": "Dative plural after mit."
                }
                """);

        WordTranslationResult result = service.word("Kindern", "de", "en", context);

        assertThat(result.dictionary()).isFalse();
        assertThat(result.aiGenerated()).isTrue();
        assertThat(result.lemma()).isEqualTo("Kind");
        assertThat(result.baseSurface()).isEqualTo("das Kind");
        assertThat(result.gender()).isEqualTo("neuter");
        assertThat(result.formTags()).containsExactly("dative", "plural");
        assertThat(result.translations()).containsExactly("children");
        verify(provider).wordAnalysis("Kindern", "de", "en", context);
        verify(cache).find(
                eq("word_analysis_v1"),
                eq("de"),
                eq("en"),
                eq("Selected word:\nKindern\n\nSentence context:\n" + context),
                anyString()
        );
    }

    @Test
    void literaryUsesSeparateCacheOperationAndIncludesContextInCacheKey() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());

        when(cache.find(eq("literary_sentence"), eq("de"), eq("en"), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(provider.literary("Ein Satz.", "de", "en", "Chapter 1", null))
                .thenReturn("A sentence.");

        String result = service.literary("Ein Satz.", "de", "en", "Chapter 1");

        assertThat(result).isEqualTo("A sentence.");
        verify(cache).find(
                eq("literary_sentence"),
                eq("de"),
                eq("en"),
                eq("Context:\nChapter 1\n\nText:\nEin Satz."),
                anyString()
        );
        verify(cache).save(
                eq("literary_sentence"),
                eq("de"),
                eq("en"),
                eq("Context:\nChapter 1\n\nText:\nEin Satz."),
                anyString(),
                eq("A sentence.")
        );
    }

    @Test
    void literaryReturnsCachedResultWithoutCallingProvider() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());

        when(cache.find(eq("literary_sentence"), eq("de"), eq("en"), anyString(), anyString()))
                .thenReturn(Optional.of("Cached sentence."));

        String result = service.literary("Ein Satz.", "de", "en", "");

        assertThat(result).isEqualTo("Cached sentence.");
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void literaryModelOverrideUsesSeparateCacheProviderAndProviderOverride() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());

        when(cache.find(eq("literary_sentence"), eq("de"), eq("en"), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(provider.literary("Ein Satz.", "de", "en", "Chapter 1", "gpt-4o"))
                .thenReturn("A better sentence.");

        String result = service.literary("Ein Satz.", "de", "en", "Chapter 1", "gpt-4o");

        assertThat(result).isEqualTo("A better sentence.");
        verify(provider).literary("Ein Satz.", "de", "en", "Chapter 1", "gpt-4o");
        verify(cache).find(
                eq("literary_sentence"),
                eq("de"),
                eq("en"),
                anyString(),
                argThat(providerKey -> providerKey.endsWith("|literaryModel=gpt-4o"))
        );
        verify(cache).save(
                eq("literary_sentence"),
                eq("de"),
                eq("en"),
                anyString(),
                argThat(providerKey -> providerKey.endsWith("|literaryModel=gpt-4o")),
                eq("A better sentence.")
        );
    }

    @Test
    void literaryRefusalIsNotCached() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());
        String refusal = "Извините, я не могу помочь с этой просьбой.";

        when(cache.find(eq("literary_sentence"), eq("el"), eq("ru"), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(provider.literary("κείμενο", "el", "ru", "Medea", null)).thenReturn(refusal);

        assertThat(service.literary("κείμενο", "el", "ru", "Medea")).isEqualTo(refusal);
        verify(cache, never()).save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void literaryIgnoresPreviouslyCachedRefusal() {
        ProviderClient provider = mock(ProviderClient.class);
        DictionaryService dictionaryService = mock(DictionaryService.class);
        TranslationCacheRepository cache = mock(TranslationCacheRepository.class);
        TranslateService service = new TranslateService(provider, dictionaryService, cache, new ObjectMapper());

        when(cache.find(eq("literary_sentence"), eq("el"), eq("ru"), anyString(), anyString()))
                .thenReturn(Optional.of("Извините, я не могу помочь с этой просьбой."));
        when(provider.literary("κείμενο", "el", "ru", "Medea", null)).thenReturn("Переведённый текст");

        assertThat(service.literary("κείμενο", "el", "ru", "Medea")).isEqualTo("Переведённый текст");
        verify(cache).save(anyString(), anyString(), anyString(), anyString(), anyString(), eq("Переведённый текст"));
    }
}
