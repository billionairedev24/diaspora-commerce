package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.ai.domain.guardrail.DefaultPiiRedactor;
import com.afrotransact.ai.domain.guardrail.Luhn;
import com.afrotransact.ai.domain.guardrail.PiiType;
import com.afrotransact.ai.domain.guardrail.Redaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiRedactionTest {

    private final DefaultPiiRedactor redactor = new DefaultPiiRedactor();

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4111 1111 1111 1111", "5500-0000-0000-0004", "378282246310005",
        "6011111111111117"})
    void luhnValidCardNumbersAreRedacted(String card) {
        Redaction r = redactor.redact("my card is " + card + " thanks");
        assertThat(r.text()).isEqualTo("my card is [CARD_1] thanks");
        assertThat(r.counts()).containsEntry(PiiType.CARD, 1);
    }

    @Test
    void numbersThatFailLuhnAreNotTreatedAsCards() {
        assertThat(Luhn.isValidCardNumber("4111111111111112")).isFalse();
        assertThat(Luhn.isValidCardNumber("4111111111111111")).isTrue();
        assertThat(Luhn.isValidCardNumber("123")).isFalse();
        Redaction r = redactor.redact("tracking 4111111111111112 please");
        assertThat(r.text()).doesNotContain("[CARD_");
    }

    @Test
    void redactsEmailsPhonesSsnAndAddresses() {
        Redaction r = redactor.redact("Email ada.obi+shop@example.co.uk or call (713) 555-0142 / +234 803 123 4567. "
                + "SSN 123-45-6789. Ship to 1200 Westheimer Road, Apt 4B please.");
        assertThat(r.text()).isEqualTo("Email [EMAIL_1] or call [PHONE_1] / [PHONE_2]. SSN [SSN_1]. "
                + "Ship to [ADDRESS_1] please.");
        assertThat(r.placeholders()).containsEntry("[EMAIL_1]", "ada.obi+shop@example.co.uk")
                .containsEntry("[ADDRESS_1]", "1200 Westheimer Road, Apt 4B");
    }

    @Test
    void abbreviatedStreetSuffixesAreAddresses() {
        assertThat(redactor.redact("deliver to 55 Main St. tomorrow").text()).isEqualTo("deliver to [ADDRESS_1] tomorrow");
        assertThat(redactor.redact("I live at 9 Baker Street").text()).isEqualTo("I live at [ADDRESS_1]");
    }

    @Test
    void sameValueGetsSamePlaceholderAndRestoreRoundTrips() {
        Redaction r = redactor.redact("a@b.com and again a@b.com, also c@d.org");
        assertThat(r.text()).isEqualTo("[EMAIL_1] and again [EMAIL_1], also [EMAIL_2]");
        assertThat(r.restore("Sent to [EMAIL_2] and [EMAIL_1]")).isEqualTo("Sent to c@d.org and a@b.com");
    }

    @Test
    void reusesPlaceholdersAcrossRedactionsOfOneTurn() {
        Redaction first = redactor.redact("email me at ada@example.com");
        Redaction second = redactor.redact("order shipped to ada@example.com and bo@example.com", first);
        assertThat(second.text()).isEqualTo("order shipped to [EMAIL_1] and [EMAIL_2]");
        assertThat(second.restore("[EMAIL_2]")).isEqualTo("bo@example.com");
    }

    @Test
    void leavesShoppingTextAlone() {
        String text = "Jollof for 20 people under $45.99, ATJOL00001, ZIP 77002, order 1045, 2 bags of rice";
        Redaction r = redactor.redact(text);
        assertThat(r.text()).isEqualTo(text);
        assertThat(r.redactedAnything()).isFalse();
    }

    @Test
    void invalidSsnPatternsAreIgnored() {
        assertThat(redactor.redact("code 000-12-3456").text()).isEqualTo("code 000-12-3456");
    }
}
