package com.rcf.imas.modules.events.service;

import com.rcf.imas.platform.error.ApiException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/** uploadEventFiles' fileFilter (eventMiddleware.js:82-101) + size limit (5MB, line 112) + fields maxCount
 *  (4 photos / 1 report, lines 113-116 -> LIMIT_UNEXPECTED_FILE, lines 119-123). Spring's MultipartResolver
 *  has already fully parsed the request by controller time, so there's no streaming per-file error
 *  callback to hook into -- this is request-level validation covering the same three failure modes. */
public final class EventUploadValidation {

    private EventUploadValidation() {}

    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/jpg", "image/webp");
    private static final Set<String> DOC_TYPES = Set.of(
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    public static void validate(MultipartFile[] photos, MultipartFile[] reports) {
        int photoCount = photos == null ? 0 : (int) java.util.Arrays.stream(photos).filter(f -> !f.isEmpty()).count();
        int reportCount = reports == null ? 0 : (int) java.util.Arrays.stream(reports).filter(f -> !f.isEmpty()).count();
        if (photoCount > 4 || reportCount > 1) {
            throw ApiException.message(400, "Too many files! Max 4 photos and 1 report allowed.");
        }
        if (photos != null) {
            for (MultipartFile f : photos) {
                if (f == null || f.isEmpty()) continue;
                if (f.getSize() > MAX_SIZE) throw ApiException.message(400, "File too large");
                if (!PHOTO_TYPES.contains(f.getContentType())) {
                    throw ApiException.message(400, "Photos must be JPG, PNG, or WEBP");
                }
            }
        }
        if (reports != null) {
            for (MultipartFile f : reports) {
                if (f == null || f.isEmpty()) continue;
                if (f.getSize() > MAX_SIZE) throw ApiException.message(400, "File too large");
                if (!DOC_TYPES.contains(f.getContentType())) {
                    throw ApiException.message(400, "Reports must be PDF or Word documents");
                }
            }
        }
    }
}
