package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a seller ships its own orders from (carrier labels, rates). Money-free, so the rules are format rules: US
 * addresses need a two-letter state and a ZIP or ZIP+4.
 *
 * @param isDefault the address the label form preselects (exactly one per organisation once any exists)
 */
public record ShipFromAddress(UUID id, String orgId, String label, String name, String company, String phone,
                              String line1, String line2, String city, String state, String postalCode,
                              String country, boolean isDefault, Instant createdAt, Instant updatedAt, long version) {

    /** Most addresses an organisation can keep. */
    public static final int MAX_PER_ORG = 20;

    private static final Pattern ZIP = Pattern.compile("\\d{5}(-\\d{4})?");
    private static final Pattern US_STATE = Pattern.compile("[A-Z]{2}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern PHONE = Pattern.compile("[+0-9 ().-]{7,40}");

    /** Trims and upper-cases the fields and checks them; throws {@link SellerRuleViolation} with every failing field. */
    public ShipFromAddress normalized() {
        String c = upper(country, "US");
        ShipFromAddress a = new ShipFromAddress(id, orgId, trim(label, "Warehouse"), trim(name, null),
                trim(company, null), trim(phone, null), trim(line1, null), trim(line2, null), trim(city, null),
                upper(state, null), trim(postalCode, null), c, isDefault, createdAt, updatedAt, version);
        Map<String, String> errors = new LinkedHashMap<>();
        length(errors, "label", a.label, 1, 60);
        length(errors, "name", a.name, 1, 120);
        length(errors, "company", a.company, 0, 120);
        length(errors, "line1", a.line1, 3, 120);
        length(errors, "line2", a.line2, 0, 120);
        length(errors, "city", a.city, 1, 80);
        if (a.phone != null && !PHONE.matcher(a.phone).matches()) {
            errors.put("phone", "Enter a phone number");
        }
        if (!COUNTRY.matcher(c).matches()) {
            errors.put("country", "Two-letter country code");
        }
        if ("US".equals(c)) {
            if (a.state == null || !US_STATE.matcher(a.state).matches()) {
                errors.put("state", "Two-letter state code");
            }
            if (a.postalCode == null || !ZIP.matcher(a.postalCode).matches()) {
                errors.put("postalCode", "5-digit ZIP or ZIP+4");
            }
        } else {
            length(errors, "state", a.state, 1, 40);
            length(errors, "postalCode", a.postalCode, 1, 12);
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-ship-from", "The ship-from address is not valid", errors);
        }
        return a;
    }

    /** The same address marked (or unmarked) as the default. */
    public ShipFromAddress withDefault(boolean value, Instant now) {
        return new ShipFromAddress(id, orgId, label, name, company, phone, line1, line2, city, state, postalCode,
                country, value, createdAt, now, version);
    }

    private static void length(Map<String, String> errors, String field, String value, int min, int max) {
        int n = value == null ? 0 : value.length();
        if (n < min || n > max) {
            errors.put(field, min > 0 ? "Required, at most " + max + " characters" : "At most " + max + " characters");
        }
    }

    private static String trim(String v, String fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return String.join(" ", v.trim().split("\\s+"));
    }

    private static String upper(String v, String fallback) {
        String t = trim(v, fallback);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }
}
