package com.afrotransact.content.domain.careers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * An open role on the careers page.
 *
 * @param id               slug id
 * @param title            role title
 * @param team             team (Engineering, Design, Operations…)
 * @param location         location (Remote (US), Houston, TX…)
 * @param type             Full-time, Hourly…
 * @param pay              pay range shown to candidates (pay transparency)
 * @param summary          one paragraph
 * @param responsibilities bullet list
 * @param requirements     bullet list
 * @param status           DRAFT, OPEN or CLOSED
 * @param sortOrder        listing order
 * @param postedAt         when it opened
 * @param updatedAt        last change
 */
public record Job(String id, String title, String team, String location, String type, String pay, String summary,
                  List<String> responsibilities, List<String> requirements, JobStatus status, int sortOrder,
                  Instant postedAt, Instant updatedAt) {

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,59}$");

    public Job {
        responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public void validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (id == null || !ID.matcher(id).matches()) {
            errors.put("id", "Lower-case letters, digits and dashes (3-60 characters)");
        }
        require(errors, "title", title, 120);
        require(errors, "team", team, 60);
        require(errors, "location", location, 80);
        require(errors, "type", type, 40);
        require(errors, "pay", pay, 60);
        require(errors, "summary", summary, 4000);
        if (status == null) {
            errors.put("status", "Required");
        }
        ContentRuleViolation.throwIfAny("content.invalid-job", "The role is invalid", errors);
    }

    public boolean open() {
        return status == JobStatus.OPEN;
    }

    private static void require(Map<String, String> errors, String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            errors.put(field, "Required, up to " + max + " characters");
        }
    }
}
