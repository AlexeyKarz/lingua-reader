package com.example.reader.dictionary;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DictionaryService {
    private final DictionaryRepository repository;

    public DictionaryService(DictionaryRepository repository) {
        this.repository = repository;
    }

    public DictionaryLookupResult lookup(String text, String sourceLang, String targetLang) {
        Optional<DictionaryEntry> entry = repository.find(sourceLang, targetLang, text);
        if (entry.isEmpty()) {
            return DictionaryLookupResult.missing();
        }

        DictionaryEntry value = entry.get();
        return new DictionaryLookupResult(
                true,
                format(value),
                value.lemma(),
                value.partOfSpeech(),
                value.gender(),
                value.definitions(),
                value.examples(),
                value.translations(),
                value.sourceName(),
                value.sourceUrl()
        );
    }

    private String format(DictionaryEntry entry) {
        List<String> lines = new ArrayList<>();

        if (!entry.partOfSpeech().isBlank() || !entry.gender().isBlank() || !entry.lemma().isBlank()) {
            StringBuilder header = new StringBuilder();
            if (!entry.partOfSpeech().isBlank()) {
                header.append(entry.partOfSpeech());
            }
            if (!entry.gender().isBlank()) {
                if (!header.isEmpty()) {
                    header.append(" · ");
                }
                header.append("gender: ").append(entry.gender());
            }
            if (!entry.lemma().isBlank() && !entry.lemma().equalsIgnoreCase(entry.surface())) {
                if (!header.isEmpty()) {
                    header.append(" · ");
                }
                header.append("lemma: ").append(entry.lemma());
            }
            lines.add(header.toString());
        }

        if (!entry.definitions().isEmpty()) {
            lines.add("Definitions:");
            for (int i = 0; i < Math.min(3, entry.definitions().size()); i++) {
                lines.add((i + 1) + ". " + entry.definitions().get(i));
            }
        }

        if (!entry.translations().isEmpty()) {
            lines.add("Translations: " + String.join(", ", entry.translations()));
        }

        if (!entry.examples().isEmpty()) {
            lines.add("Examples:");
            entry.examples().stream().limit(2).forEach(example -> lines.add("- " + example));
        }

        if (!entry.sourceName().isBlank()) {
            lines.add("Source: " + entry.sourceName());
        }

        return String.join("\n", lines);
    }
}
