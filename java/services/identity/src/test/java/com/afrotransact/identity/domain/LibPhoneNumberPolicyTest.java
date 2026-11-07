package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LibPhoneNumberPolicyTest {

    private final PhoneNumberPolicy policy = new LibPhoneNumberPolicy();

    @Test
    void normalisesE164AndNationalFormats() {
        assertThat(policy.check("+1 (713) 249-0142", null).e164()).isEqualTo("+17132490142");
        assertThat(policy.check("(713) 249-0142", "US").e164()).isEqualTo("+17132490142");
        PhoneCheck nigeria = policy.check("0803 123 4567", "NG");
        assertThat(nigeria.valid()).isTrue();
        assertThat(nigeria.e164()).isEqualTo("+2348031234567");
        assertThat(nigeria.region()).isEqualTo("NG");
        assertThat(policy.check("07911 123456", "GB").e164()).isEqualTo("+447911123456");
    }

    @Test
    void rejectsLandlinesInvalidNumbersAndMissingCountry() {
        PhoneCheck landline = policy.check("+44 20 7946 0958", null);
        assertThat(landline.valid()).isFalse();
        assertThat(landline.error()).contains("mobile");
        assertThat(policy.check("+1 555 0100", null).valid()).isFalse();
        assertThat(policy.check("7132490142", null).error()).contains("country code");
        assertThat(policy.check("not a number", "US").valid()).isFalse();
        assertThat(policy.check("", "US").valid()).isFalse();
    }

    @Test
    void rejectsLettersEvenWhenTheyWouldFormAVanityNumber() {
        assertThat(policy.check("713-ABC-0142", "US").error()).contains("digits only");
        assertThat(policy.check("(713) 249-014x", "US").valid()).isFalse();
        assertThat(policy.check("+1 800 FLOWERS", null).valid()).isFalse();
    }

    @Test
    void rejectsTooShortAndWrongRegionNumbers() {
        assertThat(policy.check("713 249", "US").error()).contains("isn't valid");
        assertThat(policy.check("0803 123 4567", "US").valid()).isFalse();
        assertThat(policy.check("(202) 555-0142", "NG").valid()).isFalse();
    }

    @Test
    void acceptsValidUsNigerianAndGhanaianMobiles() {
        assertThat(policy.check("(713) 249-0142", "US").e164()).isEqualTo("+17132490142");
        assertThat(policy.check("803 123 4567", "NG").e164()).isEqualTo("+2348031234567");
        assertThat(policy.check("024 123 4567", "GH").e164()).isEqualTo("+233241234567");
        assertThat(policy.check("+233 24 123 4567", "US").e164()).isEqualTo("+233241234567");
    }
}
