package com.afrotransact.content.domain.privacy;

import java.util.Set;

/** US states and the District of Columbia accepted as a state of residence on privacy requests. */
public final class UsStates {

    private static final Set<String> CODES = Set.of("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV",
            "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA",
            "WA", "WV", "WI", "WY");

    private UsStates() {
    }

    public static boolean isState(String code) {
        return code != null && CODES.contains(code);
    }
}
