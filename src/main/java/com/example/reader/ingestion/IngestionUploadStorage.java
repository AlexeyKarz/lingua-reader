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

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("EPUB file is required");
        }

        String originalFilename = file.getOriginalFilename() == null ? "book.epub" : file.getOriginalFilename();
        String safeFilename = sanitizeFilename(originalFilename);
        if (!safeFilename.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new IllegalArgumentException("Only EPUB files are supported");
        }

        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + "-" + safeFilename).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new IllegalArgumentException("Invalid upload filename");
            }
            file.transferTo(target);
            return target.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not store uploaded EPUB", e);
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
