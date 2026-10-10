package com.afrotransact.content.domain.legal;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {placeholder}} tokens in legal markdown from the site configuration (phone, emails, address,
 * SMS program name) so contact details are never typed into documents. Unknown tokens are left as they are.
 */
public final class LegalTemplate {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private LegalTemplate() {
    }

    public static String render(String markdown, Map<String, String> values) {
        Matcher m = TOKEN.matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
