package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S262: every pre-order announcement link carries UTM tags, and an order from the link is credited to them. */
class PreorderUtmTest {

    @Test
    void anAnnouncementLinkCarriesTheOrderAttributionAndAllFourUtmTags() {
        assertThat(PreorderUtm.query("email", "LUB-7", "announce")).isEqualTo("src=email&po=LUB-7&cr=announce"
                + "&utm_source=email&utm_medium=email&utm_campaign=preorder-LUB-7&utm_content=announce");
        assertThat(PreorderUtm.query("push", "LUB-7", "remind")).as("push links are the app source")
                .startsWith("src=app&po=LUB-7&cr=remind&utm_source=push&utm_medium=push&");
        assertThat(PreorderUtm.socialQuery("instagram", "ig", "LUB-7", "story")).isEqualTo("src=ig&po=LUB-7&cr=story"
                + "&utm_source=instagram&utm_medium=social&utm_campaign=preorder-LUB-7&utm_content=story");
    }

    @Test
    void everyCommsKindThatSendsALinkHasASlot() {
        assertThat(PreorderUtm.SLOTS).containsEntry(CommKind.ANNOUNCE, "announce")
                .containsEntry(CommKind.REMIND_48H, "remind").containsEntry(CommKind.LAST_CALL_6H, "last")
                .containsEntry(CommKind.CLOSED, "closed").containsEntry(CommKind.OUT_FOR_DELIVERY, "ofd");
    }

    @Test
    void anOrderIsCreditedToTheMessageItsTrackingTagNames() {
        assertThat(PreorderUtm.ofTrackingTag("src=sms&cr=last&po=LUB-7", "LUB-7")).contains(
                new PreorderUtm.Tags("sms", "sms", "preorder-LUB-7", "last"));
        assertThat(PreorderUtm.ofTrackingTag("src=app&cr=waitlist&po=LUB-7", "LUB-7")).contains(
                new PreorderUtm.Tags("push", "push", "preorder-LUB-7", "waitlist"));
        assertThat(PreorderUtm.ofTrackingTag("src=ig&cr=story&po=LUB-7", "LUB-7")).as("a social post").isEmpty();
        assertThat(PreorderUtm.ofTrackingTag("src=email&po=LUB-7", "LUB-7")).as("no message slot").isEmpty();
        assertThat(PreorderUtm.ofTrackingTag("src=email&cr=hero&po=LUB-7", "LUB-7")).as("an on-site placement")
                .isEmpty();
        assertThat(PreorderUtm.ofTrackingTag(null, "LUB-7")).isEmpty();
    }
}
