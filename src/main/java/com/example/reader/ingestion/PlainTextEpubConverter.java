package com.example.reader.ingestion;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class PlainTextEpubConverter {
    private static final byte[] EPUB_MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    public void convert(byte[] textBytes, Path target, String title, String lang) throws IOException {
        String text = decodeUtf8(textBytes);
        List<String> blocks = splitBlocks(text);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("TXT file does not contain readable text");
        }

        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        String safeTitle = blankToDefault(title, "Uploaded text");
        String safeLang = blankToDefault(lang, "und").toLowerCase(Locale.ROOT);
        String identifier = "urn:uuid:" + UUID.randomUUID();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            writeStoredEntry(zip, "mimetype", EPUB_MIMETYPE);
            writeEntry(zip, "META-INF/container.xml", containerXml());
            writeEntry(zip, "OEBPS/content.opf", packageDocument(identifier, safeTitle, safeLang));
            writeEntry(zip, "OEBPS/nav.xhtml", navDocument(safeTitle, safeLang));
            writeEntry(zip, "OEBPS/text.xhtml", textDocument(safeTitle, safeLang, blocks));
        }
    }

    private String decodeUtf8(byte[] textBytes) {
        String text = new String(textBytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private List<String> splitBlocks(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawBlocks = normalized.split("\\n\\s*\\n+");
        List<String> blocks = new ArrayList<>();
        for (String rawBlock : rawBlocks) {
            String block = rawBlock.trim().replaceAll("[ \\t\\f\\x0B]+", " ");
            block = block.replaceAll("\\n+", " ");
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private String textDocument(String title, String lang, List<String> blocks) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escapeXml(title)).append("</h1>\n");
        for (String block : blocks) {
            if (isLikelyChapterHeading(block)) {
                body.append("<h2>").append(escapeXml(block)).append("</h2>\n");
            } else {
                body.append("<p>").append(escapeXml(block)).append("</p>\n");
            }
        }

        return """
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="%s" lang="%s">
                <head>
                  <title>%s</title>
                </head>
                <body>
                %s</body>
                </html>
                """.formatted(escapeXml(lang), escapeXml(lang), escapeXml(title), body);
    }

    private boolean isLikelyChapterHeading(String block) {
        if (block.length() > 90) return false;
        String lower = block.toLowerCase(Locale.ROOT);
        return lower.matches("^(chapter|stave|part|book|section)\\b.*")
                || lower.matches("^(chapitre|capitulo|capítulo|kapitel|parte|libro|teil)\\b.*")
                || lower.matches("^[ivxlcdm]+\\.?$")
                || lower.matches("^\\d+\\.?$");
    }

    private String packageDocument(String identifier, String title, String lang) {
        return """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">%s</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:language>%s</dc:language>
                    <meta property="dcterms:modified">%s</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="text" href="text.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="text"/>
                  </spine>
                </package>
                """.formatted(escapeXml(identifier), escapeXml(title), escapeXml(lang), Instant.now().toString());
    }

    private String navDocument(String title, String lang) {
        return """
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="%s" lang="%s">
                <head>
                  <title>%s</title>
                </head>
                <body>
                  <nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops">
                    <ol>
                      <li><a href="text.xhtml">%s</a></li>
                    </ol>
                  </nav>
                </body>
                </html>
                """.formatted(escapeXml(lang), escapeXml(lang), escapeXml(title), escapeXml(title));
    }

    private String containerXml() {
        return """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;
    }

    private void writeStoredEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
