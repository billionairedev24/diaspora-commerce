package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultPasswordPolicyTest {

    private final PasswordPolicy policy = new DefaultPasswordPolicy();
    private final List<String> personal = List.of("Adaeze", "Okonkwo-Bello", "ada.ok");

    @Test
    void acceptsStrongPassword() {
        assertThat(policy.violations("Plantain-Jollof-2026", personal)).isEmpty();
    }

    @Test
    void requiresLengthUpperLowerAndDigit() {
        assertThat(policy.violations("short1A", personal)).contains("Use at least 12 characters");
        assertThat(policy.violations("alllowercase123", personal)).contains("Add an upper-case letter");
        assertThat(policy.violations("ALLUPPERCASE123", personal)).contains("Add a lower-case letter");
        assertThat(policy.violations("NoDigitsAtAllHere", personal)).contains("Add a number");
    }

    @Test
    void rejectsNamesAndEmailLocalPartIgnoringCaseAndAccents() {
        assertThat(policy.violations("MyNameIsADAEZE2026", personal))
                .contains("Don't use your name or email in the password");
        assertThat(policy.violations("Bello-Rocks-2026x", personal))
                .contains("Don't use your name or email in the password");
        assertThat(policy.violations("Ádaeze-is-me-2026", personal))
                .contains("Don't use your name or email in the password");
        assertThat(policy.violations("Hello-ada-world-2026", List.of("Ada", "Li", "ada")))
                .contains("Don't use your name or email in the password");
    }

    @Test
    void ignoresVeryShortNameParts() {
        assertThat(policy.violations("Libraries-Open-2026", List.of("Li", "Wu", "lw"))).isEmpty();
    }
}
