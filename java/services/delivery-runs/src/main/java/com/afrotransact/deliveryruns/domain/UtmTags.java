package com.afrotransact.deliveryruns.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The UTM tags of a visit or sign-up (S262): where the shopper came from. Only the three campaign tags are kept (never
 * a visitor id, address or the full URL), each cleaned to a short lower-case token so an attacker cannot fill the
 * counters with arbitrary text; anything that does not fit becomes {@link #OTHER}.
 *
 * @param source   utm_source: the channel or network (email, sms, whatsapp, facebook, share...)
 * @param medium   utm_medium: email, sms, push, whatsapp, social, advertorial...
 * @param campaign utm_campaign: e.g. {@code delivery-run-bastrop-tx-20261024}
 */
public record UtmTags(String source, String medium, String campaign) {

    public static final String OTHER = "other";
    /** What a visit or sign-up with no UTM tags is counted under. */
    public static final String DIRECT = "direct";

    private static final Pattern TOKEN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final int MAX_SOURCE = 40;
    private static final int MAX_CAMPAIGN = 80;

    /** The tags as sent: nulls and blanks become {@code direct}; unusable values become {@code other}. */
    public static UtmTags clean(String source, String medium, String campaign) {
        return new UtmTags(token(source, MAX_SOURCE, DIRECT), token(medium, MAX_SOURCE, DIRECT),
                token(campaign, MAX_CAMPAIGN, DIRECT));
    }

    /** True when the visitor arrived with at least one tag. */
    public boolean tagged() {
        return !(DIRECT.equals(source) && DIRECT.equals(medium) && DIRECT.equals(campaign));
    }

    /** The bucket every combination beyond the per-day limit is counted under. */
    public static UtmTags overflow() {
        return new UtmTags(OTHER, OTHER, OTHER);
    }

    private static String token(String raw, int max, String blank) {
        if (raw == null || raw.isBlank()) {
            return blank;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.length() <= max && TOKEN.matcher(t).matches() ? t : OTHER;
    }
}
