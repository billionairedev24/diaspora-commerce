package com.afrotransact.content.domain.site;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Customer-facing contacts.
 *
 * @param supportPhone        customer service number in E.164 (+18335550199), used for tel: links and SMS HELP
 * @param supportPhoneDisplay the same number formatted for display
 * @param emails              email per purpose (support, privacy, accessibility, legal, press, partners, careers)
 * @param address             company address
 */
public record Contacts(String supportPhone, String supportPhoneDisplay, Map<String, String> emails,
                       PostalAddress address) {

    /** Purposes every configuration must have an email for. */
    public static final List<String> REQUIRED_EMAILS =
            List.of("support", "privacy", "accessibility", "legal", "press", "partners", "careers");

    public Contacts {
        emails = emails == null ? Map.of() : new TreeMap<>(emails);
    }

    public String email(String purpose) {
        return emails.getOrDefault(purpose, "");
    }
}
