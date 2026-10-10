package com.afrotransact.content.domain.legal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Normalises a visitor's cookie choices: required categories are always on, categories marked
 * {@code gpcOptOut} are forced off when the Global Privacy Control signal is present, unknown keys are rejected.
 */
public final class ConsentPolicy {

    private ConsentPolicy() {
    }

    public static Map<String, Boolean> normalise(Map<String, Boolean> requested, List<CookieCategory> categories,
                                                 boolean gpc) {
        Map<String, Boolean> in = requested == null ? Map.of() : requested;
        for (String key : in.keySet()) {
            if (categories.stream().noneMatch(c -> c.key().equals(key))) {
                throw new ContentRuleViolation("content.unknown-cookie-category", "Unknown cookie category " + key,
                        Map.of("categories." + key, "Unknown category"));
            }
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (CookieCategory c : categories) {
            boolean on;
            if (c.required()) {
                on = true;
            } else if (gpc && c.gpcOptOut()) {
                on = false;
            } else {
                on = Boolean.TRUE.equals(in.get(c.key()));
            }
            out.put(c.key(), on);
        }
        return out;
    }
}
