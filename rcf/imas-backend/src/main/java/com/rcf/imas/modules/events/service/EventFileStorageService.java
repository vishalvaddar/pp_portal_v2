package com.rcf.imas.modules.events.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reproduces uploadEventFiles' multer diskStorage.filename callback (eventMiddleware.js:37-73) VERBATIM,
 * including the eventTitle(camelCase)-vs-event_title(snake_case) filename-source landmine (ground truth
 * §4, §7.3, plan Firm Decision 4 / Deferred): the filename source is a request param literally named
 * "eventTitle", falling back to the literal "event" if absent -- NOT the "event_title" field the rest of
 * this module's validation uses. Ported as-is; flagged, not fixed.
 */
@Service
public class EventFileStorageService {

    private final Path photosDir;
    private final Path reportsDir;

    public EventFileStorageService(@Value("${imas.event-storage-path}") String basePath) {
        Path base = Paths.get(basePath);
        this.photosDir = base.resolve("photos");
        this.reportsDir = base.resolve("reports");
        try {
            Files.createDirectories(photosDir);
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record StoredFile(String diskPath, String storedFilename, String originalFilename) {}

    static String cleanName(String eventTitleField) {
        String t = (eventTitleField == null || eventTitleField.isBlank()) ? "event" : eventTitleField;
        return t.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    }

    static String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        // Node's path.extname() reads ONLY the final path segment. Strip any dir separators first so a crafted
        // upload filename (e.g. "x.jpg/../../evil") can't smuggle path-traversal into the generated store name.
        String base = originalFilename;
        int sep = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (sep >= 0) base = base.substring(sep + 1);
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot).toLowerCase() : "";
    }

    /** Photos: <cleanName>-<n><ext>, n = 1-based per-request counter (eventMiddleware.js:56-66, max 4). */
    public StoredFile storePhoto(MultipartFile file, String eventTitleField, int index) {
        String filename = cleanName(eventTitleField) + "-" + index + extensionOf(file.getOriginalFilename());
        Path target = photosDir.resolve(filename);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StoredFile(target.toString(), filename, file.getOriginalFilename());
    }

    /** Reports: <cleanName>-report<ext>, no counter -- a second report in the same request overwrites the
     *  first ON DISK (eventMiddleware.js:67-70); DB rows are managed separately per endpoint. */
    public StoredFile storeReport(MultipartFile file, String eventTitleField) {
        String filename = cleanName(eventTitleField) + "-report" + extensionOf(file.getOriginalFilename());
        Path target = reportsDir.resolve(filename);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StoredFile(target.toString(), filename, file.getOriginalFilename());
    }

    public Path photosDir() { return photosDir; }
    public Path reportsDir() { return reportsDir; }
}
