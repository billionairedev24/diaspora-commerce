package com.afrotransact.content.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A syntactically valid email address, normalised to lower case. Mirrors what the storefront checks
 * (a local part, an @, a domain with a dot and a 2+ letter TLD) plus RFC 5321 length limits.
 *
 * @param value normalised address
 */
public record EmailAddress(String value) {

    private static final Pattern SHAPE = Pattern.compile(
            "^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    public EmailAddress {
        if (!valid(value)) {
            throw invalid("email");
        }
    }

    /** Trims and lower-cases {@code raw}; throws {@link ContentRuleViolation} on the given field when invalid. */
    public static EmailAddress parse(String raw, String field) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!valid(v)) {
            throw invalid(field);
        }
        return new EmailAddress(v);
    }

    public static boolean valid(String v) {
        if (v == null || v.length() > 254) {
            return false;
        }
        int at = v.indexOf('@');
        return at > 0 && at <= 64 && SHAPE.matcher(v).matches();
    }

    private static ContentRuleViolation invalid(String field) {
        return new ContentRuleViolation("content.invalid-email", "Enter a valid email address.",
                Map.of(field, "Enter a valid email address."));
    }

    @Override
    public String toString() {
        return value;
    }
}
