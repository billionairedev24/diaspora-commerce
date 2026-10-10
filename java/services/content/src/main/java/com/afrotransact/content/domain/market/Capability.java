package com.afrotransact.content.domain.market;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Per-state capability switches. */
public enum Capability {
    MARKETPLACE("marketplace"),
    SERVICES("services"),
    FOOD("food"),
    PICKUP("pickup"),
    TAX_REGISTRATION("taxRegistration");

    private final String key;

    Capability(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Capability fromKey(String key) {
        for (Capability c : values()) {
            if (c.key.equalsIgnoreCase(key) || c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        throw new ContentRuleViolation("content.unknown-capability", "Unknown capability " + key);
    }
}
