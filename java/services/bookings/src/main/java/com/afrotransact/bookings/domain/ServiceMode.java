package com.afrotransact.bookings.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Where a service happens. AfroTransact services are in person only: there is no online, video or remote mode, and
 * nothing accepts one (see docs/engineering/service-areas.md).
 */
public enum ServiceMode {
    /** The customer goes to the provider's studio, salon, tailor shop, office or venue ("You go to them"). */
    IN_PERSON,
    /** The provider travels to the customer's address: home visit, mobile service, catering ("Comes to you"). */
    AT_HOME;

    /**
     * Parses a client-supplied mode. Online, virtual, remote and video are refused with a clear error, not a generic
     * deserialisation failure.
     */
    public static ServiceMode parse(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (ServiceMode m : values()) {
            if (m.name().equals(v)) {
                return m;
            }
        }
        throw new BookingsRuleViolation("bookings.in-person-only",
                "Services are in person only: choose IN_PERSON or AT_HOME", Map.of("modes",
                        "AfroTransact services are in person only (IN_PERSON or AT_HOME); online, virtual and remote "
                                + "services are not offered"));
    }
}
