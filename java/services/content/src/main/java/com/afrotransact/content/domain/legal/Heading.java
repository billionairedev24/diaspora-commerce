package com.afrotransact.content.domain.legal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An "On this page" entry.
 *
 * @param id    anchor id (slug)
 * @param title heading text
 */
public record Heading(String id, String title) {

    /** Level-2 headings ({@code ## …}) of a markdown body, with unique slugs. */
    public static List<Heading> outline(String markdown) {
        List<Heading> out = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## ")) {
                String title = line.substring(3).trim();
                String slug = slug(title);
                int n = used.merge(slug, 1, Integer::sum);
                out.add(new Heading(n == 1 ? slug : slug + "-" + n, title));
            }
        }
        return out;
    }

    static String slug(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String s = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "section" : s;
    }
}
