package com.example.reader.reader;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class BookTextService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookTextService.class);
    private final Map<String, String> cache = new HashMap<>();

    public String page(String bookId, String lang, int page, int sizeWords) {
        String key = bookId + "_" + lang;
        String text = cache.computeIfAbsent(key, k -> loadText(bookId, lang));
        if (text.isEmpty()) return "";

        // tokenize by words, keep punctuation as separators
        List<String> words = new ArrayList<>();
        var scanner = new Scanner(text);
        scanner.useDelimiter("\\s+");
        while (scanner.hasNext()) words.add(scanner.next());
        scanner.close();

        int totalPages = (int) Math.ceil(words.size() / (double) sizeWords);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        int from = page * sizeWords;
        int to = Math.min(from + sizeWords, words.size());

        // join back to a chunk; optional: insert line breaks after sentence end
        return String.join(" ", words.subList(from, to));
    }

    public int totalPages(String bookId, String lang, int sizeWords) {
        String key = bookId + "_" + lang;
        String text = cache.computeIfAbsent(key, k -> loadText(bookId, lang));
        if (text.isEmpty()) return 0;
        int wc = Math.max(1, text.split("\\s+").length);
        return (int) Math.ceil(wc / (double) sizeWords);
    }

    private String loadText(String bookId, String lang) {
        String path = "books/" + bookId + "_" + lang + ".txt"; // or "static/books/..." if you choose B
        try {
            var res = new ClassPathResource(path);
            if (!res.exists()) {
                log.error("Resource not found on classpath: {}", path);
                return "";
            }
            var bytes = res.getContentAsByteArray();
            return new String(bytes, StandardCharsets.UTF_8)
                    .replace("\r\n","\n").replace("\r","\n")
                    .replaceAll("[ \\t]+"," ").trim();
        } catch (Exception e) {
            log.error("Failed to read resource {}: {}", path, e.toString());
            return "";
        }
    }
}
