package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S262: UTM tags are cleaned to short tokens before they are counted, so nothing else can be stored. */
class UtmTagsTest {

    @Test
    void tagsAreLowerCasedAndTrimmed() {
        UtmTags t = UtmTags.clean(" WhatsApp ", "Social", "Delivery-Run-bastrop-tx-20261024");
        assertThat(t).isEqualTo(new UtmTags("whatsapp", "social", "delivery-run-bastrop-tx-20261024"));
        assertThat(t.tagged()).isTrue();
    }

    @Test
    void nothingSentIsDirectAndNotTagged() {
        assertThat(UtmTags.clean(null, " ", "")).isEqualTo(new UtmTags("direct", "direct", "direct"));
        assertThat(UtmTags.clean(null, null, null).tagged()).isFalse();
        assertThat(UtmTags.clean("sms", null, null).tagged()).isTrue();
    }

    @Test
    void anythingThatIsNotAShortTokenBecomesOther() {
        assertThat(UtmTags.clean("a b", "<script>", "x".repeat(81))).isEqualTo(UtmTags.overflow());
        assertThat(UtmTags.clean("x".repeat(41), "ok", "ok").source()).isEqualTo("other");
        assertThat(UtmTags.clean("ünï", "ok", "ok").source()).as("no personal text slips in").isEqualTo("other");
        assertThat(UtmTags.clean("jane@example.com", "ok", "ok").source()).isEqualTo("other");
    }
}
