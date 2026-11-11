package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.afrotransact.orders.domain.DeliveryQuote.Answer;
import com.afrotransact.orders.domain.DeliveryQuote.Decline;
import com.afrotransact.orders.domain.DeliveryQuote.Escalation;
import com.afrotransact.orders.domain.DeliveryQuote.Holder;
import com.afrotransact.orders.domain.QuoteSnapshot.LineKey;
import com.afrotransact.orders.domain.QuoteSnapshot.Place;
import org.junit.jupiter.api.Test;

class DeliveryQuoteTest {

    private static final Instant T0 = Instant.parse("2026-11-09T15:00:00Z"); // a Monday
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static QuoteSnapshot snapshot() {
        return new QuoteSnapshot("cart", "addr", "USD", List.of(), List.of(), new Place("Marfa", "TX", "79843", "US"),
                "Ada Obi", QuoteContact.EMAIL, null);
    }

    private static DeliveryQuote requested() {
        return DeliveryQuote.requested("q1", "DQ-2026-AAAAAA", "cust", snapshot(), T0);
    }

    private static Answer answer(Instant at) {
        return new Answer(1800, "Rural route", "agent", "Amina Bello", at, at.plusSeconds(48 * 3600));
    }

    @Test
    void aRequestIsAssignedReleasedAndAgreedInOrder() {
        DeliveryQuote q = requested().assign(new Holder("agent", "Amina Bello", "agent", "Amina Bello", T0), T0);
        assertThat(q.status()).isEqualTo(QuoteStatus.IN_PROGRESS);
        assertThat(q.assigned()).isTrue();
        DeliveryQuote back = q.release(T0.plusSeconds(60));
        assertThat(back.status()).isEqualTo(QuoteStatus.REQUESTED);
        assertThat(back.assigned()).isFalse();
        // the wait never restarts when it moves between people
        assertThat(back.stageEnteredAt()).isEqualTo(T0);

        DeliveryQuote ready = q.agree(answer(T0.plusSeconds(120)), T0.plusSeconds(120));
        assertThat(ready.status()).isEqualTo(QuoteStatus.READY);
        assertThat(ready.stageEnteredAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(ready.usable(T0.plusSeconds(3600))).isTrue();
        assertThat(ready.usable(T0.plusSeconds(120 + 48 * 3600))).isFalse();
    }

    @Test
    void closedCasesRefuseStaffActions() {
        DeliveryQuote declined = requested().decline(new Decline("No route", "agent", "Amina Bello", T0), T0);
        assertThat(declined.status()).isEqualTo(QuoteStatus.DECLINED);
        assertThatThrownBy(() -> declined.assign(Holder.NONE, T0)).isInstanceOf(OrdersStateConflict.class);
        assertThatThrownBy(() -> declined.agree(answer(T0), T0)).isInstanceOf(OrdersStateConflict.class);
        assertThatThrownBy(() -> declined.escalate(new Escalation(1, T0, "late", List.of(), List.of()), T0))
                .isInstanceOf(OrdersStateConflict.class);
        assertThatThrownBy(() -> requested().expire(T0)).isInstanceOf(OrdersStateConflict.class);
    }

    @Test
    void aReadyQuoteIsUsedOnceAndRestoredWhenTheOrderFails() {
        DeliveryQuote ready = requested().agree(answer(T0), T0);
        DeliveryQuote used = ready.use("order-1", T0.plusSeconds(10));
        assertThat(used.status()).isEqualTo(QuoteStatus.USED);
        assertThat(used.usedOrderId()).isEqualTo("order-1");
        assertThatThrownBy(() -> used.use("order-2", T0.plusSeconds(20))).isInstanceOf(OrdersStateConflict.class);
        DeliveryQuote back = used.restore(T0.plusSeconds(30));
        assertThat(back.status()).isEqualTo(QuoteStatus.READY);
        assertThat(back.usedOrderId()).isNull();
        assertThatThrownBy(() -> ready.use("order-3", T0.plusSeconds(48 * 3600 + 1)))
                .isInstanceOf(OrdersStateConflict.class);
        assertThatThrownBy(() -> ready.restore(T0)).isInstanceOf(OrdersStateConflict.class);
    }

    @Test
    void escalationOnlyGoesUp() {
        DeliveryQuote one = requested().escalate(new Escalation(1, T0, "late", List.of("a"), List.of("Amina")), T0);
        assertThat(one.escalation().level()).isEqualTo(1);
        assertThat(one.escalatedTo("a")).isTrue();
        assertThat(one.escalatedTo("b")).isFalse();
        assertThat(one.escalate(new Escalation(1, T0, "again", List.of(), List.of()), T0)).isSameAs(one);
        // agreeing clears it: the wait is now the shopper's
        assertThat(one.agree(answer(T0), T0).escalation().level()).isZero();
    }

    @Test
    void slaCountsBusinessDaysAndStartsOnMondayForAWeekendRequest() {
        // Monday 09:00 Chicago -> Tuesday 09:00
        Instant mon = Instant.parse("2026-11-09T15:00:00Z");
        assertThat(QuoteSla.dueAt(mon, 1, CHICAGO)).isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
        // Friday 09:00 -> Monday 09:00 (the weekend does not count)
        Instant fri = Instant.parse("2026-11-13T15:00:00Z");
        assertThat(QuoteSla.dueAt(fri, 1, CHICAGO)).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));
        // Saturday: the clock starts Monday 00:00, so the answer is due Tuesday 00:00
        Instant sat = Instant.parse("2026-11-14T15:00:00Z");
        assertThat(QuoteSla.dueAt(sat, 1, CHICAGO)).isEqualTo(Instant.parse("2026-11-17T06:00:00Z"));
        Instant due = QuoteSla.dueAt(mon, 1, CHICAGO);
        assertThat(QuoteSla.state(mon, due, mon.plusSeconds(3600), 75)).isEqualTo(QuoteSla.State.ON_TIME);
        assertThat(QuoteSla.state(mon, due, mon.plusSeconds(20 * 3600), 75)).isEqualTo(QuoteSla.State.DUE_SOON);
        assertThat(QuoteSla.state(mon, due, due, 75)).isEqualTo(QuoteSla.State.OVERDUE);
    }

    @Test
    void theCartHashIgnoresOrderAndPricesAndTheAddressHashIgnoresCaseAndSpacing() {
        String a = QuoteSnapshot.hashLines(List.of(new LineKey("A1", "org", 1), new LineKey("B2", "org", 2)));
        String b = QuoteSnapshot.hashLines(List.of(new LineKey("B2", "org", 2), new LineKey("A1", "org", 1)));
        assertThat(a).isEqualTo(b);
        assertThat(QuoteSnapshot.hashLines(List.of(new LineKey("A1", "org", 2), new LineKey("B2", "org", 2))))
                .isNotEqualTo(a);
        ShipTo one = new ShipTo("x", "Ada", null, "9 Far  Road", null, "Marfa", "TX", "79843-1234", "US", null);
        ShipTo same = new ShipTo("y", "Ada", null, "9 far road", null, "MARFA", "tx", "79843", "us", null);
        ShipTo other = new ShipTo("x", "Ada", null, "10 Far Road", null, "Marfa", "TX", "79843", "US", null);
        assertThat(QuoteSnapshot.hashAddress(one)).isEqualTo(QuoteSnapshot.hashAddress(same));
        assertThat(QuoteSnapshot.hashAddress(one)).isNotEqualTo(QuoteSnapshot.hashAddress(other));
    }

    @Test
    void settingsAreValidatedFieldByField() {
        QuoteSettings ok = new QuoteSettings(1, 75, 4, 24, null, null, null, null, 500, 50000, 48, 5, 15, null, null);
        assertThat(ok.problems()).isEmpty();
        QuoteSettings bad = new QuoteSettings(0, 100, 0, 721, null, null, null, new QuoteSettings.Target("r", null),
                600, 500, 0, 0, 0, null, null);
        assertThat(bad.problems()).containsKeys("slaBusinessDays", "dueSoonPercent", "unassignedAfterHours",
                "level2AfterHours", "maxAmountMinor", "validityHours", "requestsPerHour", "duplicateWindowMinutes",
                "level2");
    }
}
