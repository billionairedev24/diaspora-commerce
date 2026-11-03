package com.afrotransact.seller.domain;

import java.util.Set;

/**
 * A catalog department as onboarding needs it: its stable node code, its name and the evidence the catalog says
 * selling in it needs on file (from the department node's compliance data, never from a list in this service).
 */
public record Department(String code, String name, Set<Requirement> requires) {

    /** Evidence a department asks the seller for. */
    public enum Requirement { FOOD_PERMIT, BEAUTY_LABELLING }

    public Department {
        requires = requires == null ? Set.of() : Set.copyOf(requires);
    }

    /** True for a well-formed catalog department code. */
    public static boolean isCode(String code) {
        return code != null && code.matches("[a-z0-9][a-z0-9-]{0,118}");
    }
}
