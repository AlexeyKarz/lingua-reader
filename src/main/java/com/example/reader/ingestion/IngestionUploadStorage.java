package com.example.reader.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Service
public class IngestionUploadStorage {
    private final Path uploadDir;

    public IngestionUploadStorage(IngestionUploadProperties properties) {
        this.uploadDir = properties.getDir().toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, String title, String lang, String contentType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("EPUB or TXT file is required");
        }

        String originalFilename = file.getOriginalFilename() == null ? "book.epub" : file.getOriginalFilename();
        String safeFilename = sanitizeFilename(originalFilename);
        String lowerFilename = safeFilename.toLowerCase(Locale.ROOT);
        if (!lowerFilename.endsWith(".epub") && !lowerFilename.endsWith(".txt")) {
            throw new IllegalArgumentException("Only EPUB and TXT files are supported");
        }

        try {
            Files.createDirectories(uploadDir);
            String storedFilename = safeFilename;
            Path target = uploadDir.resolve(UUID.randomUUID() + "-" + storedFilename).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new IllegalArgumentException("Invalid upload filename");
            }
            file.transferTo(target);
            return target.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not store uploaded file", e);
        }
    }

    private String sanitizeFilename(String filename) {
        String normalizedSeparators = filename.replace('\\', '/');
        int lastSlash = normalizedSeparators.lastIndexOf('/');
        String baseName = (lastSlash >= 0 ? normalizedSeparators.substring(lastSlash + 1) : normalizedSeparators).trim();
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            return "book.epub";
        }
        return sanitized;
    }

}
