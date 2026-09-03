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
    private final PlainTextEpubConverter plainTextEpubConverter;

    public IngestionUploadStorage(IngestionUploadProperties properties, PlainTextEpubConverter plainTextEpubConverter) {
        this.uploadDir = properties.getDir().toAbsolutePath().normalize();
        this.plainTextEpubConverter = plainTextEpubConverter;
    }

    public String store(MultipartFile file, String title, String lang) {
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
            String storedFilename = lowerFilename.endsWith(".txt")
                    ? stripExtension(safeFilename) + ".epub"
                    : safeFilename;
            Path target = uploadDir.resolve(UUID.randomUUID() + "-" + storedFilename).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new IllegalArgumentException("Invalid upload filename");
            }
            if (lowerFilename.endsWith(".txt")) {
                plainTextEpubConverter.convert(file.getBytes(), target, title, lang);
            } else {
                file.transferTo(target);
            }
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

    private String stripExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }
}
