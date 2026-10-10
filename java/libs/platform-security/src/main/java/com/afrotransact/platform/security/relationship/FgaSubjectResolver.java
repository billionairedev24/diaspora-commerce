package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.Actor;

/**
 * Maps the calling {@link Actor} to the model's user: {@code user:<sub>} for people, {@code client:<sub>} for
 * OAuth clients (services, partner apps, AI agents). Override the bean to change the mapping.
 */
public interface FgaSubjectResolver {

    String subjectOf(Actor actor);

    /** The platform-wide mapping. */
    static FgaSubjectResolver standard() {
        return new StandardFgaSubjectResolverImpl();
    }
}
