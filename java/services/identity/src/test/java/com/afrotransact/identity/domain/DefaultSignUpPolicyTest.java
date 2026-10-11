package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DefaultSignUpPolicyTest {

    private final SignUpPolicy policy = new DefaultSignUpPolicy(new DefaultPasswordPolicy(), new LibPhoneNumberPolicy());

    @Test
    void normalisesValidSignUp() {
        ValidatedSignUp v = policy.validate(new SignUpDetails(" Adaeze ", "O'Brien-Okafor", "Ada@Example.COM",
                "(713) 249-0142", "US", "Plantain-Jollof-2026", "Plantain-Jollof-2026", true));
        assertThat(v.firstName()).isEqualTo("Adaeze");
        assertThat(v.email()).isEqualTo("ada@example.com");
        assertThat(v.phoneE164()).isEqualTo("+17132490142");
    }

    @Test
    void acceptsAccentsApostrophesHyphensAndOtherScripts() {
        assertThat(policy.validName("Chiamaka")).isTrue();
        assertThat(policy.validName("Zoë")).isTrue();
        assertThat(policy.validName("N'Golo")).isTrue();
        assertThat(policy.validName("Jean-Luc")).isTrue();
        assertThat(policy.validName("Mary Jane")).isTrue();
        assertThat(policy.validName("ሰላም")).isTrue();
        assertThat(policy.validName("R2D2")).isFalse();
        assertThat(policy.validName("-Ada")).isFalse();
        assertThat(policy.validName("Ada  Lovelace")).isFalse();
        assertThat(policy.validName("")).isFalse();
    }

    @Test
    void reportsEveryFailingField() {
        assertThatThrownBy(() -> policy.validate(new SignUpDetails("4da", "", "not-an-email", "+44 20 7946 0958", null,
                "weak", "different", false)))
                .isInstanceOf(IdentityRuleViolation.class)
                .satisfies(ex -> assertThat(((IdentityRuleViolation) ex).fieldErrors()).containsOnlyKeys(
                        "firstName", "lastName", "email", "phone", "password", "confirmPassword", "termsAccepted"));
    }

    @Test
    void theMobileNumberIsOptional() {
        ValidatedSignUp v = policy.validate(new SignUpDetails("Ada", "Obi", "ada@example.com", " ", null,
                "Plantain-Jollof-2026", "Plantain-Jollof-2026", true));
        assertThat(v.phoneE164()).isNull();
    }
}
