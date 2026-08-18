package com.example.reader.dictionary;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DictionaryService {
    private final DictionaryRepository repository;

    public DictionaryService(DictionaryRepository repository) {
        this.repository = repository;
    }

    public DictionaryLookupResult lookup(String text, String sourceLang, String targetLang) {
        String selected = DictionaryNormalizer.normalizeWord(text);
        List<DictionaryEntry> entries = curate(repository.findEntries(sourceLang, targetLang, text), selected);
        if (entries.isEmpty()) {
            return DictionaryLookupResult.missing();
        }

        DictionaryEntry value = entries.getFirst();
        return new DictionaryLookupResult(
                true,
                format(entries),
                value.lemma(),
                value.partOfSpeech(),
                value.gender(),
                value.baseSurface(),
                value.baseNormalized(),
                value.definitions(),
                value.examples(),
                value.translations(),
                entries,
                value.sourceName(),
                value.sourceUrl()
        );
    }

    private List<DictionaryEntry> curate(List<DictionaryEntry> entries, String selected) {
        Map<String, DictionaryEntry> deduped = new LinkedHashMap<>();
        for (DictionaryEntry entry : entries) {
            String key = signature(entry);
            DictionaryEntry current = deduped.get(key);
            if (current == null || richness(entry) > richness(current)) {
                deduped.put(key, entry);
            }
        }

        return deduped.values().stream()
                .sorted(Comparator
                        .comparingInt((DictionaryEntry entry) -> sectionRank(entry, selected))
                        .thenComparingInt(this::contentRank)
                        .thenComparing(DictionaryEntry::surface, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DictionaryEntry::id))
                .toList();
    }

    private String signature(DictionaryEntry entry) {
        return String.join("|",
                entry.normalized().toLowerCase(Locale.ROOT),
                entry.partOfSpeech().toLowerCase(Locale.ROOT),
                String.join("\u001f", entry.definitions()).toLowerCase(Locale.ROOT),
                String.join("\u001f", entry.translations()).toLowerCase(Locale.ROOT),
                String.join("\u001f", entry.examples()).toLowerCase(Locale.ROOT)
        );
    }

    private int richness(DictionaryEntry entry) {
        int score = 0;
        if (!entry.baseNormalized().isBlank()) score += 8;
        if (!entry.translations().isEmpty()) score += 4;
        if (!entry.examples().isEmpty()) score += 2;
        if (!entry.gender().isBlank()) score += 2;
        if (!entry.partOfSpeech().isBlank()) score += 1;
        if (!entry.lemma().isBlank()) score += 1;
        return score;
    }

    private int sectionRank(DictionaryEntry entry, String selected) {
        if (isFormEntry(entry)) {
            return 2;
        }
        if (!entry.normalized().equals(selected)) {
            return 1;
        }
        return 0;
    }

    private int contentRank(DictionaryEntry entry) {
        if (!entry.translations().isEmpty()) {
            return 0;
        }
        if (!entry.definitions().isEmpty() || !entry.examples().isEmpty()) {
            return 1;
        }
        return 2;
    }

    private boolean isFormEntry(DictionaryEntry entry) {
        if (!entry.baseNormalized().isBlank() && !entry.baseNormalized().equals(entry.normalized())) {
            return true;
        }
        if (entry.formTags().stream().anyMatch(tag -> tag.equalsIgnoreCase("form-of"))) {
            return true;
        }
        if (!entry.translations().isEmpty()) {
            return false;
        }
        return entry.definitions().stream().allMatch(this::looksLikeFormDefinition);
    }

    private boolean looksLikeFormDefinition(String definition) {
        String text = definition.toLowerCase(Locale.ROOT);
        return text.contains(" des verbs ")
                || text.contains(" des substantivs ")
                || text.contains(" des adjektivs ")
                || text.contains(" form of ")
                || text.contains("plural of ")
                || text.contains("singular of ");
    }

    private String format(List<DictionaryEntry> entries) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            DictionaryEntry entry = entries.get(i);
            if (i > 0) {
                lines.add("");
            }
            lines.add(entry.surface());
            addEntryText(lines, entry);
        }
        return String.join("\n", lines);
    }

    private void addEntryText(List<String> lines, DictionaryEntry entry) {
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

        if (!entry.baseSurface().isBlank() && !entry.baseSurface().equalsIgnoreCase(entry.surface())) {
            String formText = "Form of: " + entry.baseSurface();
            if (!entry.formTags().isEmpty()) {
                formText += " (" + String.join(", ", entry.formTags()) + ")";
            }
            lines.add(formText);
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
    }
}
