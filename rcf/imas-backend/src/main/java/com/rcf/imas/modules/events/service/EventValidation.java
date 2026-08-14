package com.rcf.imas.modules.events.service;

import com.rcf.imas.platform.error.ApiException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Static ports of eventMiddleware.js's validateEventId, validateEventBody, sanitizeEventNumbers. */
public final class EventValidation {

    private EventValidation() {}

    /** validateEventId (eventMiddleware.js:175-186): Number(req.params.id); (!id || isNaN(id)) -> 400
     *  {message:"Invalid event ID"}. Java rejects non-integer strings too (see plan Deferred: Node's
     *  Number() would technically accept "3.5" as non-NaN/non-zero and let it flow through to a query;
     *  Java treats that as invalid up front -- a stricter, wire-safe simplification of a no-op edge case). */
    public static long validateEventId(String idParam) {
        long id;
        try {
            id = Long.parseLong(idParam);
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiException.message(400, "Invalid event ID");
        }
        if (id == 0) throw ApiException.message(400, "Invalid event ID");
        return id;
    }

    /** validateEventBody (eventMiddleware.js:135-168). Node's date-order check does `new Date(start) >
     *  new Date(end)` on plain YYYY-MM-DD strings (UTC-midnight parse, timezone-agnostic for this
     *  comparison, ground truth §7.13) -- Java uses LocalDate comparison, equivalent for this exact check. */
    public static void validateEventBody(String eventTypeId, String eventTitle,
                                          String eventStartDate, String eventEndDate) {
        if (eventTypeId == null || eventTypeId.isBlank() || !isNumeric(eventTypeId)) {
            throw ApiException.message(400, "Valid event_type_id is required");
        }
        if (eventTitle == null || eventTitle.trim().length() < 3) {
            throw ApiException.message(400, "Event title must be at least 3 characters");
        }
        if (eventStartDate == null || eventStartDate.isBlank()
                || eventEndDate == null || eventEndDate.isBlank()) {
            throw ApiException.message(400, "Start and end dates are required");
        }
        try {
            LocalDate start = LocalDate.parse(eventStartDate);
            LocalDate end = LocalDate.parse(eventEndDate);
            if (start.isAfter(end)) {
                throw ApiException.message(400, "End date must be after start date");
            }
        } catch (DateTimeParseException e) {
            throw ApiException.message(400, "End date must be after start date");
        }
    }

    /** sanitizeEventNumbers (eventMiddleware.js:193-213): "" or absent -> null; a numeric string becomes
     *  its Number()-canonical form (LANDMINE: drops leading zeros, ground truth §3 LANDMINE #2); a
     *  non-numeric string passes through unchanged (Node's `!isNaN(field)` guard only rewrites numerics). */
    public static String sanitizeNumeric(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!isNumeric(raw)) return raw;
        double d = Double.parseDouble(raw);
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
