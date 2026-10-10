package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SubscriptionTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void shopperSubscriptionIsActiveWithPerChannelConsent() {
        Subscription s = Subscription.forUser(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                EnumSet.of(Channel.EMAIL, Channel.WHATSAPP), "location-page", "u", NOW);
        assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.activeChannels()).containsExactlyInAnyOrder(Channel.EMAIL, Channel.WHATSAPP);
        assertThat(s.consents().get(Channel.WHATSAPP).consentedAt()).isEqualTo(NOW);
        assertThat(s.consents().get(Channel.WHATSAPP).source()).isEqualTo("location-page");

        Subscription noWhatsapp = s.withdraw(Set.of(Channel.WHATSAPP), NOW.plusSeconds(60));
        assertThat(noWhatsapp.activeChannels()).containsExactly(Channel.EMAIL);
        assertThat(noWhatsapp.consents().get(Channel.WHATSAPP).withdrawnAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(noWhatsapp.status()).isEqualTo(SubscriptionStatus.ACTIVE);

        Subscription none = noWhatsapp.withdraw(Set.of(), NOW.plusSeconds(120));
        assertThat(none.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(none.activeChannels()).isEmpty();

        Subscription back = none.grant(Set.of(Channel.SMS), "checkout", NOW.plusSeconds(180));
        assertThat(back.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(back.activeChannels()).containsExactly(Channel.SMS);
    }

    @Test
    void emailSubscriberNeedsDoubleOptInAndEmailOnly() {
        Subscription s = Subscription.forEmail(UUID.randomUUID(), UUID.randomUUID(), " Ada@Example.COM ", "page",
                "hash", NOW.plus(Duration.ofHours(72)), "u", NOW);
        assertThat(s.email()).isEqualTo("ada@example.com");
        assertThat(s.status()).isEqualTo(SubscriptionStatus.PENDING_CONFIRMATION);
        assertThat(s.activeChannels()).isEmpty();
        assertThatThrownBy(() -> s.grant(Set.of(Channel.SMS), "page", NOW)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.sign-in-required");

        Subscription confirmed = s.confirm(NOW.plusSeconds(600));
        assertThat(confirmed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(confirmed.confirmedAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(confirmed.confirm(NOW.plusSeconds(700))).as("confirming again is a no-op").isSameAs(confirmed);
        assertThat(confirmed.activeChannels()).containsExactly(Channel.EMAIL);
    }

    @Test
    void expiredConfirmationIsRefused() {
        Subscription s = Subscription.forEmail(UUID.randomUUID(), UUID.randomUUID(), "ada@example.com", "page",
                "hash", NOW.plusSeconds(60), "u", NOW);
        assertThatThrownBy(() -> s.confirm(NOW.plusSeconds(61))).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.confirmation-expired");
    }

    @Test
    void invalidEmailIsRefused() {
        assertThatThrownBy(() -> Subscription.normaliseEmail("not-an-email")).isInstanceOf(RuleViolation.class);
    }
}
