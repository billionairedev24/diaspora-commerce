package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FirstPartySignInRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void mfaChallengeIsBoundToItsClientAndAllowsFiveWrongCodes() {
        SignInChallenge c = SignInChallenge.mfa(UUID.randomUUID(), "gateway-bff", T0);
        assertThat(c.usableBy("gateway-bff", T0)).isTrue();
        assertThat(c.usableBy("storefront", T0)).isFalse();
        assertThat(c.usableBy("gateway-bff", T0.plus(SignInChallenge.MFA_TTL))).isFalse();
        for (int i = 0; i < SignInChallenge.MAX_ATTEMPTS - 1; i++) {
            c = c.failedAttempt();
        }
        assertThat(c.attemptsLeft()).isEqualTo(1);
        assertThat(c.usableBy("gateway-bff", T0)).isTrue();
        assertThat(c.failedAttempt().usableBy("gateway-bff", T0)).isFalse();
        assertThat(c.completed(T0).usableBy("gateway-bff", T0)).isFalse();
    }

    @Test
    void loginTicketIsRedeemableOnlyByItsClientBeforeItExpires() {
        LoginTicket t = new LoginTicket("h", UUID.randomUUID(), "gateway-bff", SignInMethod.PASSWORD, T0,
                T0.plus(LoginTicket.TTL), null);
        assertThat(t.redeemableBy("gateway-bff", T0.plusSeconds(59))).isTrue();
        assertThat(t.redeemableBy("gateway-bff", T0.plusSeconds(60))).isFalse();
        assertThat(t.redeemableBy("storefront", T0)).isFalse();
    }

    @Test
    void devicePlatformsDefaultToTheirPushTransport() {
        assertThat(DevicePlatform.IOS.defaultProvider()).isEqualTo(PushProvider.APNS);
        assertThat(DevicePlatform.ANDROID.defaultProvider()).isEqualTo(PushProvider.FCM);
        assertThat(DevicePlatform.WEB.defaultProvider()).isEqualTo(PushProvider.FCM);
        assertThat(PushProvider.APNS.wire()).isEqualTo("apns");
    }

    @Test
    void preferencesValidateLocaleAndTimeZone() {
        assertThat(NotificationPreferences.check("fr-CA", "America/Toronto")).isEmpty();
        assertThat(NotificationPreferences.check(null, " ")).isEmpty();
        assertThat(NotificationPreferences.check("??", "Mars/Olympus")).containsKeys("locale", "timeZone");
        assertThat(NotificationPreferences.canonicalLocale("fr-ca")).isEqualTo("fr-CA");
        NotificationPreferences d = NotificationPreferences.defaults(UUID.randomUUID());
        assertThat(d.email() && d.sms() && d.push()).isTrue();
        assertThat(d.marketingOptIn()).isFalse();
    }
}
