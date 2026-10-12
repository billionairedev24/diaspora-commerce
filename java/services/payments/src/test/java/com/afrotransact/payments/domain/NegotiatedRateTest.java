package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NegotiatedRateTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static NegotiatedRate create(String category, Instant starts, Instant ends) {
        return NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.COMMISSION, category, 700, starts,
                ends, "  Volume deal  ", null, " SF-1001 ", "fin-1", NOW);
    }

    @Test
    void aReasonIsRequiredAndValuesAreNormalised() {
        NegotiatedRate r = create(" Fashion ", null, NOW.plus(Duration.ofDays(30)));
        assertThat(r.category()).isEqualTo("fashion");
        assertThat(r.reason()).isEqualTo("Volume deal");
        assertThat(r.reference()).isEqualTo("SF-1001");
        assertThat(r.negotiatedBy()).isEqualTo("fin-1");
        assertThat(r.startsAt()).isEqualTo(NOW);
        assertThat(create(null, null, null).category()).isEqualTo("*");
        assertThatThrownBy(() -> NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.COMMISSION,
                "*", 700, null, null, " ", null, null, "fin-1", NOW))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsKey("reason"));
        assertThatThrownBy(() -> create("*", NOW.minus(Duration.ofDays(1)), null))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.negotiated-rate-backdated"));
        assertThatThrownBy(() -> create("*", NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(1))))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.COMMISSION,
                "*", 10_001, null, null, "x", null, null, "fin-1", NOW)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void statusFollowsTheDatesAndEndingIsFinal() {
        Instant start = NOW.plus(Duration.ofDays(1));
        Instant end = NOW.plus(Duration.ofDays(10));
        NegotiatedRate r = create("*", start, end);
        assertThat(r.status(NOW)).isEqualTo(NegotiatedRateStatus.SCHEDULED);
        assertThat(r.status(start)).isEqualTo(NegotiatedRateStatus.ACTIVE);
        assertThat(r.status(end)).isEqualTo(NegotiatedRateStatus.EXPIRED);
        assertThat(r.dueForExpiry(end)).isTrue();
        NegotiatedRate expired = r.expire(end);
        assertThat(expired.dueForExpiry(end.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> expired.end(end.plusSeconds(1), "fin-2", "late"))
                .isInstanceOfSatisfying(PaymentsStateConflict.class,
                        e -> assertThat(e.code()).isEqualTo("payments.negotiated-rate-ended"));

        NegotiatedRate ended = r.end(start.plusSeconds(60), "fin-2", " Seller left the programme ");
        assertThat(ended.status(start.plusSeconds(60))).isEqualTo(NegotiatedRateStatus.ENDED);
        assertThat(ended.endReason()).isEqualTo("Seller left the programme");
        assertThat(ended.activeAt(start.plusSeconds(59))).isTrue();
        assertThat(ended.activeAt(start.plusSeconds(60))).isFalse();
        assertThat(ended.dueForExpiry(end.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> ended.end(start.plusSeconds(120), "fin-2", "again"))
                .isInstanceOf(PaymentsStateConflict.class);
        assertThatThrownBy(() -> r.end(NOW, "fin-2", " ")).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void overlapsOnlyForTheSameOrgTypeAndCategoryInTime() {
        NegotiatedRate open = create("*", null, null);
        NegotiatedRate later = create("*", NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(9)));
        assertThat(open.overlaps(later)).isTrue();
        assertThat(open.overlaps(create("fashion", null, null))).isFalse();
        NegotiatedRate first = create("*", null, NOW.plus(Duration.ofDays(5)));
        assertThat(first.overlaps(later)).isFalse();
        NegotiatedRate service = NegotiatedRate.create(UUID.randomUUID(), "org_a", NegotiatedRateType.CONNECTION_FEE,
                "*", 500, null, null, "x", null, null, "fin-1", NOW);
        assertThat(open.overlaps(service)).isFalse();
        // An ended rate no longer blocks a new one after its end.
        NegotiatedRate ended = open.end(NOW.plus(Duration.ofDays(1)), "fin-2", "renegotiated");
        assertThat(ended.overlaps(later)).isFalse();
    }
}
