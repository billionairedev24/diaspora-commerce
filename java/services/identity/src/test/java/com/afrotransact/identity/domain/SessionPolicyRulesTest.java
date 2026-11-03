package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SessionPolicyRulesTest {

    private static void check(SessionAudience a, SessionPolicyOverrides o) {
        SessionPolicyRules.validate(a, o, SessionPolicy.resolve(SessionPolicyDefaults.of(a), o));
    }

    private static Map<String, String> errorsOf(SessionAudience a, SessionPolicyOverrides o) {
        try {
            check(a, o);
        } catch (IdentityRuleViolation ex) {
            return ex.fieldErrors();
        }
        return Map.of();
    }

    @Test
    void builtInDefaultsAreWhatThePolicySays() {
        assertThat(SessionPolicyDefaults.of(SessionAudience.STAFF)).isEqualTo(new SessionPolicy(15, 480, 60));
        assertThat(SessionPolicyDefaults.of(SessionAudience.SELLER)).isEqualTo(new SessionPolicy(30, 720, 60));
        assertThat(SessionPolicyDefaults.of(SessionAudience.CUSTOMER_WEB)).isEqualTo(new SessionPolicy(43_200, 129_600, 0));
        assertThat(SessionPolicyDefaults.of(SessionAudience.CUSTOMER_APP)).isEqualTo(new SessionPolicy(43_200, 129_600, 0));
        assertThat(SessionPolicyDefaults.of(SessionAudience.DRIVER)).isEqualTo(new SessionPolicy(840, 840, 0));
    }

    @Test
    void everyDefaultIsValid() {
        for (SessionAudience a : SessionAudience.values()) {
            assertThatCode(() -> check(a, SessionPolicyOverrides.none())).doesNotThrowAnyException();
        }
    }

    @Test
    void idleIsBetweenOneMinuteAndOneHundredEightyDays() {
        assertThat(errorsOf(SessionAudience.CUSTOMER_WEB, new SessionPolicyOverrides(0, null, 0))).containsKey("idleTimeoutMinutes");
        assertThat(errorsOf(SessionAudience.CUSTOMER_WEB, new SessionPolicyOverrides(180 * 24 * 60 + 1, 365 * 24 * 60, 0)))
                .containsKey("idleTimeoutMinutes");
        assertThat(errorsOf(SessionAudience.CUSTOMER_WEB, new SessionPolicyOverrides(180 * 24 * 60, 365 * 24 * 60, 0))).isEmpty();
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(1, null, 0))).isEmpty();
    }

    @Test
    void absoluteIsBetweenFiveMinutesAndThreeHundredSixtyFiveDays() {
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(null, 4, null))).containsKey("absoluteTimeoutMinutes");
        assertThat(errorsOf(SessionAudience.CUSTOMER_WEB, new SessionPolicyOverrides(null, 365 * 24 * 60 + 1, null)))
                .containsKey("absoluteTimeoutMinutes");
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(5, 5, 0))).isEmpty();
    }

    @Test
    void idleCannotExceedAbsolute() {
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(600, null, null)))
                .containsEntry("idleTimeoutMinutes", "The idle timeout cannot be longer than the session length");
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(null, 10, null)))
                .containsKey("idleTimeoutMinutes");
    }

    @Test
    void warningIsZeroToThreeHundredSecondsAndShorterThanIdle() {
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(null, null, 301))).containsKey("warnSeconds");
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(null, null, -1))).containsKey("warnSeconds");
        // the default warning of staff is 60 s, so a one-minute idle timeout leaves no room for it
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(1, null, null))).containsKey("warnSeconds");
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(2, null, 119))).isEmpty();
        assertThat(errorsOf(SessionAudience.STAFF, new SessionPolicyOverrides(2, null, 120))).containsKey("warnSeconds");
    }

    @Test
    void theViolationIsAnInvalidSessionPolicyWithFieldMessages() {
        assertThatThrownBy(() -> check(SessionAudience.DRIVER, new SessionPolicyOverrides(0, null, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("identity.invalid-session-policy");
                    assertThat(ex.getMessage()).contains("Driver app");
                });
    }

    @Test
    void valuesEqualToTheDefaultAreStoredAsUseTheDefault() {
        SessionPolicy defaults = SessionPolicyDefaults.of(SessionAudience.STAFF);
        assertThat(new SessionPolicyOverrides(15, 480, 60).withoutDefaults(defaults).isEmpty()).isTrue();
        assertThat(new SessionPolicyOverrides(20, 480, null).withoutDefaults(defaults))
                .isEqualTo(new SessionPolicyOverrides(20, null, null));
    }

    @Test
    void audiencesParseByWireName() {
        assertThat(SessionAudience.parse(" Customer-Web ")).contains(SessionAudience.CUSTOMER_WEB);
        assertThat(SessionAudience.parse("admin")).isEmpty();
        assertThat(SessionAudience.parse(null)).isEmpty();
    }
}
