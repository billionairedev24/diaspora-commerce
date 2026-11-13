package com.afrotransact.content.domain.home;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * One community of the registry: a canonical key, labels per locale, aliases that merge spelling variants, and an optional
 * editorial image (used only when it passes the real-photo check at the gateway).
 */
public record CommunityEntry(String key, Map<String, String> labels, List<String> aliases, String imageUrl,
                             Instant updatedAt) {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    public CommunityEntry {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** Lower-case, trimmed, separators as single dashes: "Nigerian ", "nigerian" and "NIGERIAN" are one value. */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        boolean dash = false;
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                if (dash && b.length() > 0) {
                    b.append('-');
                }
                b.append(c);
                dash = false;
            } else {
                dash = true;
            }
        }
        return b.toString();
    }

    public void validate() {
        Map<String, String> bad = new LinkedHashMap<>();
        if (key == null || key.length() > 60 || !KEY.matcher(key).matches()) {
            bad.put("key", "Lower-case words joined by dashes, up to 60 characters");
        }
        String en = labels.get("en");
        if (en == null || en.isBlank()) {
            bad.put("labels.en", "An English label is required");
        }
        labels.forEach((locale, label) -> {
            if (!List.of("en", "fr", "es", "ar", "yo").contains(locale) || label.length() > 80) {
                bad.put("labels." + locale, "Use en, fr, es, ar or yo, up to 80 characters");
            }
        });
        if (aliases.size() > 20) {
            bad.put("aliases", "At most 20 aliases");
        }
        for (String a : aliases) {
            if (a == null || a.isBlank() || a.length() > 60) {
                bad.put("aliases", "Each alias is 1 to 60 characters");
            }
        }
        if (imageUrl != null && (imageUrl.length() > 500 || !imageUrl.startsWith("https://"))) {
            bad.put("imageUrl", "An https address up to 500 characters");
        }
        if (!bad.isEmpty()) {
            throw new ContentRuleViolation("content.community-invalid", "The community is not valid", bad);
        }
    }
}
