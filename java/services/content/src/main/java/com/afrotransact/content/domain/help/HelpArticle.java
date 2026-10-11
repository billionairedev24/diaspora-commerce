package com.afrotransact.content.domain.help;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A help center page (help, shipping &amp; delivery, press, advertise…): a title and its sections. Copy lives in
 * content-service so it changes without a deployment.
 *
 * @param slug      URL segment under /help (the hub is {@code help-center})
 * @param topic     help center topic
 * @param title     page title
 * @param summary   one line shown in the article list
 * @param sections  headings and Markdown bodies, in order
 * @param status    DRAFT or PUBLISHED
 * @param sortOrder order within the list
 * @param updatedAt last change
 * @param version   optimistic-locking version
 */
public record HelpArticle(String slug, HelpTopic topic, String title, String summary, List<HelpSection> sections,
                          HelpArticleStatus status, int sortOrder, Instant updatedAt, long version) {

    /** The help center hub rendered at /help. */
    public static final String HUB = "help-center";

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,59}$");
    private static final int MAX_SECTIONS = 30;

    public HelpArticle {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public void validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (slug == null || !SLUG.matcher(slug).matches()) {
            errors.put("slug", "Lower-case letters, digits and dashes (2-60 characters)");
        }
        if (topic == null) {
            errors.put("topic", "Required");
        }
        require(errors, "title", title, 120);
        require(errors, "summary", summary, 300);
        if (status == null) {
            errors.put("status", "Required");
        }
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            errors.put("sections", "Between 1 and " + MAX_SECTIONS + " sections");
        }
        for (int i = 0; i < sections.size(); i++) {
            HelpSection s = sections.get(i);
            if (s == null || s.heading() == null || s.heading().isBlank() || s.heading().length() > 120) {
                errors.put("sections[" + i + "].heading", "Required, up to 120 characters");
            }
            if (s == null || s.body() == null || s.body().isBlank() || s.body().length() > 8000) {
                errors.put("sections[" + i + "].body", "Required, up to 8000 characters");
            }
        }
        ContentRuleViolation.throwIfAny("content.invalid-help-article", "The help article is invalid", errors);
    }

    public boolean published() {
        return status == HelpArticleStatus.PUBLISHED;
    }

    /** Case-insensitive match on the title, summary and section text. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (title.toLowerCase(Locale.ROOT).contains(q) || summary.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return sections.stream().anyMatch(s -> s.heading().toLowerCase(Locale.ROOT).contains(q)
                || s.body().toLowerCase(Locale.ROOT).contains(q));
    }

    private static void require(Map<String, String> errors, String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            errors.put(field, "Required, up to " + max + " characters");
        }
    }
}
