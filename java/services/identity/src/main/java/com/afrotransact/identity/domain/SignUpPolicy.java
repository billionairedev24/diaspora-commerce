package com.afrotransact.identity.domain;

/** Validates the unified sign-up form (same form for customers, sellers and providers). */
public interface SignUpPolicy {

    /** Throws {@link IdentityRuleViolation} listing every failing field. */
    ValidatedSignUp validate(SignUpDetails details);

    /** Validates a person's name; letters (any script, with accents), spaces, hyphens and apostrophes. */
    boolean validName(String name);
}
