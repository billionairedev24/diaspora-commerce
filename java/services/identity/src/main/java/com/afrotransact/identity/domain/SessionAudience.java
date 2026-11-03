package com.afrotransact.identity.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Who is signed in, for the inactivity and session-length policy: one policy per audience. */
public enum SessionAudience {

    CUSTOMER_WEB("customer-web", "Customers on the web"),
    CUSTOMER_APP("customer-app", "Customer mobile app"),
    SELLER("seller", "Seller Center (sellers and providers)"),
    STAFF("staff", "Staff console"),
    DRIVER("driver", "Driver app");

    private final String key;
    private final String label;

    SessionAudience(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The wire name, for example {@code customer-web}. */
    public String key() {
        return key;
    }

    /** What staff see instead of the key. */
    public String label() {
        return label;
    }

    public static Optional<SessionAudience> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(a -> a.key.equals(k)).findFirst();
    }
}
