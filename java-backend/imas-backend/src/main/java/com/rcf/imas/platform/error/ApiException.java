package com.rcf.imas.platform.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** Carries the exact legacy JSON body key ("error" or "message") per endpoint contract. */
public class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, Object> body = new LinkedHashMap<>();

    private ApiException(int status, String key, String text) {
        super(text);
        this.status = status;
        this.body.put(key, text);
    }

    /** No default key -- caller builds the body entirely via .with(), for envelopes like
     *  {success:false, msg:"..."} (events module's "msg"-keyed routes, ground truth §5 rows 9-12) that
     *  don't fit the single-key error()/message() shape. */
    private ApiException(int status) {
        super((String) null);
        this.status = status;
    }

    public static ApiException error(int status, String text)   { return new ApiException(status, "error", text); }
    public static ApiException message(int status, String text) { return new ApiException(status, "message", text); }
    public static ApiException of(int status) { return new ApiException(status); }

    public ApiException with(String key, Object value) { body.put(key, value); return this; }

    public int status() { return status; }
    public Map<String, Object> body() { return body; }
}
