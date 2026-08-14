package com.rcf.imas.modules.merge.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parity port of the live matching primitives in mergeModel.js.
 * Only the deterministic key + the prefix-ratio suggestion are ported;
 * the Dice-coefficient getSuggestion is dead code in Node (never invoked) and is omitted.
 */
@Component
public class MergeMatching {

    /** Node: text?.toUpperCase().replace(/[^A-Z]/g,"") — uppercase then keep only A–Z. Null-safe (null → null). */
    public String normalizeText(String text) {
        if (text == null) return null;
        return text.toUpperCase().replaceAll("[^A-Z]", "");
    }

    /** Node: (name||"").toLowerCase().replace(/[^a-z0-9]/g,"") — the deterministic join key. */
    public String generateStudentNameKey(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Node suggestValue: prefix-char-match ratio over normalized strings; returns the best option
     * whose ratio > 0.4, else null. `options` are already normalizeText'd block keys.
     */
    public String suggestValue(String input, List<String> options) {
        String key = normalizeText(input);
        if (key == null) key = "";
        String best = null;
        double score = 0.0;
        for (String option : options) {
            String optionKey = normalizeText(option);
            if (optionKey == null) optionKey = "";
            int match = 0;
            int min = Math.min(optionKey.length(), key.length());
            for (int i = 0; i < min; i++) if (optionKey.charAt(i) == key.charAt(i)) match++;
            int max = Math.max(optionKey.length(), key.length());
            double ratio = max == 0 ? 0.0 : (double) match / max;   // JS 0/0 → NaN, never > score; use 0.0
            if (ratio > score) { score = ratio; best = option; }
        }
        return score > 0.4 ? best : null;
    }
}
