package com.example.reader.dictionary;

import com.example.reader.ingestion.IngestionProgressSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
public class DictionaryWarmupService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:['’.-][\\p{L}\\p{M}]+)?");
    private static final String SOURCE_NAME = "Wiktionary via Kaikki/Wiktextract";
    private static final String SOURCE_URL = "https://kaikki.org/";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path dictionaryDir;

    public DictionaryWarmupService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${mlr.dictionary.dir:/dictionaries}") String dictionaryDir
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.dictionaryDir = Path.of(dictionaryDir).toAbsolutePath().normalize();
    }

    public WarmupResult warmup(
            long editionId,
            String sourceLang,
            List<String> targetLangs,
            IngestionProgressSink sink,
            int chapters,
            int paragraphs,
            int sentences
    ) throws Exception {
        Set<String> terms = collectTerms(editionId);
        if (terms.isEmpty()) return new WarmupResult(0, 0, 0, null);

        List<String> targets = targetLangs.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.equals(sourceLang)).distinct().toList();
        if (targets.isEmpty()) return new WarmupResult(terms.size(), 0, 0, null);

        Path dictionary = resolveDictionary(sourceLang);
        Map<String, Set<String>> missingByTarget = new HashMap<>();
        for (String target : targets) {
            Set<String> missing = new HashSet<>(terms);
            if (!missing.isEmpty()) {
                List<String> existing = jdbc.queryForList(
                        "select distinct normalized from dictionary_entry where source_lang = ? and target_lang = ? and normalized = any (cast(? as text[]))",
                        String.class, sourceLang, target, missing.toArray(String[]::new)
                );
                missing.removeAll(existing);
            }
            missingByTarget.put(target, missing);
        }
        Set<String> wanted = new HashSet<>();
        missingByTarget.values().forEach(wanted::addAll);
        if (wanted.isEmpty()) return new WarmupResult(terms.size(), 0, 0, dictionary);

        publish(sink, "dictionary", "Scanning " + dictionary.getFileName() + " for " + wanted.size() + " book words",
                chapters, paragraphs, sentences);
        long scanned = 0;
        int imported = 0;
        try (BufferedReader reader = open(dictionary)) {
            String line;
            while ((line = reader.readLine()) != null) {
                scanned++;
                if (scanned % 250_000 == 0) {
                    publish(sink, "dictionary", "Scanned " + String.format(Locale.ROOT, "%,d", scanned)
                                    + " dictionary rows; imported " + imported,
                            chapters, paragraphs, sentences);
                }
                if (line.isBlank()) continue;
                JsonNode root;
                try {
                    root = mapper.readTree(line);
                } catch (Exception ignored) {
                    continue;
                }
                String word = text(root, "word");
                String normalized = DictionaryNormalizer.normalizeWord(word);
                if (!wanted.contains(normalized)) continue;
                String entryLang = text(root, "lang_code").toLowerCase(Locale.ROOT);
                if (!entryLang.isBlank() && !entryLang.equals(sourceLang)) continue;

                for (String target : targets) {
                    if (!missingByTarget.get(target).contains(normalized)) continue;
                    Entry entry = extract(root, word, normalized, target);
                    if (entry == null) continue;
                    upsert(sourceLang, target, entry);
                    imported++;
                }
            }
        }
        publish(sink, "dictionary", "Dictionary warmup imported " + imported + " entries for "
                        + String.join(", ", targets).toUpperCase(Locale.ROOT),
                chapters, paragraphs, sentences);
        return new WarmupResult(terms.size(), scanned, imported, dictionary);
    }

    private Set<String> collectTerms(long editionId) {
        Set<String> terms = new LinkedHashSet<>();
        jdbc.query("select text from segment where edition_id = ? and level = 'sentence'", rs -> {
            Matcher matcher = WORD.matcher(rs.getString("text"));
            while (matcher.find()) {
                String normalized = DictionaryNormalizer.normalizeWord(matcher.group());
                if (normalized.length() >= 2) terms.add(normalized);
            }
        }, editionId);
        return terms;
    }

    private Path resolveDictionary(String sourceLang) {
        List<String> names = List.of(sourceLang + ".jsonl.gz", sourceLang + ".jsonl",
                sourceLang + "-dictionary.jsonl.gz", sourceLang + "-dictionary.jsonl");
        for (String name : names) {
            Path candidate = dictionaryDir.resolve(name).normalize();
            if (candidate.startsWith(dictionaryDir) && Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalArgumentException("Dictionary file for '" + sourceLang + "' not found in " + dictionaryDir
                + "; expected " + sourceLang + ".jsonl or " + sourceLang + ".jsonl.gz");
    }

    private BufferedReader open(Path path) throws Exception {
        InputStream input = Files.newInputStream(path);
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            input = new GZIPInputStream(input, 64 * 1024);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 256 * 1024);
    }

    private Entry extract(JsonNode root, String word, String normalized, String target) {
        List<String> definitions = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        List<String> translations = new ArrayList<>();
        String base = "";
        List<String> formTags = new ArrayList<>();
        JsonNode senses = root.path("senses");
        if (senses.isArray()) {
            for (JsonNode sense : senses) {
                addTextValues(definitions, sense.path("glosses"), 4);
                if (definitions.size() < 4) addTextValues(definitions, sense.path("raw_glosses"), 4);
                addTextValues(examples, sense.path("examples"), 4);
                addTranslations(translations, sense.path("translations"), target, 4);
                if (base.isBlank()) base = firstBase(sense);
                addTextValues(formTags, sense.path("tags"), 4);
            }
        }
        addTranslations(translations, root.path("translations"), target, 4);
        if (definitions.isEmpty() && examples.isEmpty() && translations.isEmpty()) return null;
        String lemma = text(root, "lemma");
        String pos = text(root, "pos");
        String gender = genders(root);
        return new Entry(word, normalized, lemma, pos, gender, base,
                DictionaryNormalizer.normalizeWord(base), formTags, definitions, examples, translations);
    }

    private void addTextValues(List<String> output, JsonNode values, int limit) {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            String candidate = value.isTextual() ? value.asText().strip()
                    : value.path("text").asText(value.path("word").asText("")).strip();
            if (!candidate.isBlank() && !output.contains(candidate)) output.add(candidate);
            if (output.size() >= limit) return;
        }
    }

    private void addTranslations(List<String> output, JsonNode values, String target, int limit) {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            String code = value.path("code").asText(value.path("lang_code").asText("")).toLowerCase(Locale.ROOT);
            if (!code.isBlank() && !code.equals(target)) continue;
            String translated = value.path("word").asText("").strip();
            if (!translated.isBlank() && !output.contains(translated)) output.add(translated);
            if (output.size() >= limit) return;
        }
    }

    private String firstBase(JsonNode sense) {
        for (String field : List.of("form_of", "alt_of", "inflection_of")) {
            JsonNode values = sense.path(field);
            if (!values.isArray()) continue;
            for (JsonNode value : values) {
                String base = value.isTextual() ? value.asText() : value.path("word").asText(value.path("text").asText(""));
                if (!base.isBlank()) return base.strip();
            }
        }
        return "";
    }

    private String genders(JsonNode root) {
        Set<String> result = new LinkedHashSet<>();
        for (String field : List.of("gender", "genders")) {
            JsonNode value = root.path(field);
            if (value.isTextual()) result.add(value.asText());
            if (value.isArray()) value.forEach(item -> result.add(item.asText()));
        }
        return String.join(", ", result);
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private void upsert(String source, String target, Entry entry) throws Exception {
        String keySource = String.join("|", source, target, entry.normalized(), entry.partOfSpeech(), entry.lemma(),
                entry.baseNormalized(), mapper.writeValueAsString(entry.definitions()), mapper.writeValueAsString(entry.translations()));
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keySource.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                insert into dictionary_entry(entry_key, source_lang, target_lang, surface, normalized, lemma,
                  part_of_speech, gender, base_surface, base_normalized, form_tags_json, definitions_json,
                  examples_json, translations_json, source_name, source_url)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?)
                on conflict (entry_key) do update set surface=excluded.surface, lemma=excluded.lemma,
                  part_of_speech=excluded.part_of_speech, gender=excluded.gender,
                  base_surface=excluded.base_surface, base_normalized=excluded.base_normalized,
                  form_tags_json=excluded.form_tags_json, definitions_json=excluded.definitions_json,
                  examples_json=excluded.examples_json, translations_json=excluded.translations_json,
                  source_name=excluded.source_name, source_url=excluded.source_url, last_accessed_at=now()
                """, key, source, target, entry.surface(), entry.normalized(), entry.lemma(), entry.partOfSpeech(),
                entry.gender(), entry.baseSurface(), entry.baseNormalized(), mapper.writeValueAsString(entry.formTags()),
                mapper.writeValueAsString(entry.definitions()), mapper.writeValueAsString(entry.examples()),
                mapper.writeValueAsString(entry.translations()), SOURCE_NAME, SOURCE_URL);
    }

    private void publish(IngestionProgressSink sink, String step, String message, int chapters, int paragraphs, int sentences) {
        if (sink != null) sink.update(step, message, chapters, paragraphs, sentences);
    }

    public record WarmupResult(int terms, long scanned, int imported, Path dictionaryFile) {}
    private record Entry(String surface, String normalized, String lemma, String partOfSpeech, String gender,
                         String baseSurface, String baseNormalized, List<String> formTags,
                         List<String> definitions, List<String> examples, List<String> translations) {}
}
