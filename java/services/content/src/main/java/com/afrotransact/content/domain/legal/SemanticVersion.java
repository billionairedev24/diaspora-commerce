package com.afrotransact.content.domain.legal;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * Document version {@code major.minor[.patch]}. Displayed without a zero patch ("3.2"), like the legal pages.
 *
 * @param major major
 * @param minor minor
 * @param patch patch
 */
public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    private static final Pattern SHAPE = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})(?:\\.(\\d{1,3}))?$");
    private static final Comparator<SemanticVersion> ORDER = Comparator.comparingInt(SemanticVersion::major)
            .thenComparingInt(SemanticVersion::minor).thenComparingInt(SemanticVersion::patch);

    public static SemanticVersion parse(String raw) {
        Matcher m = raw == null ? null : SHAPE.matcher(raw.trim());
        if (m == null || !m.matches()) {
            throw new ContentRuleViolation("content.invalid-version", "Use a version like 3.2 or 3.2.1",
                    Map.of("version", "Use a version like 3.2 or 3.2.1"));
        }
        return new SemanticVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
    }

    @Override
    public int compareTo(SemanticVersion o) {
        return ORDER.compare(this, o);
    }

    public boolean newerThan(SemanticVersion o) {
        return compareTo(o) > 0;
    }

    @Override
    public String toString() {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }
}
