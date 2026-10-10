package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReservationTest {

    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");
    private final Reservation held = Reservation.hold(UUID.randomUUID(), "ORD-1", "key-1", now, now.plusSeconds(900));

    @Test
    void heldReservationExpiresAfterTtlUnlessConfirmed() {
        assertThat(held.expiredAt(now.plusSeconds(899))).isFalse();
        assertThat(held.expiredAt(now.plusSeconds(900))).isTrue();
        Reservation confirmed = held.confirm(now.plusSeconds(60));
        assertThat(confirmed.expiresAt()).isNull();
        assertThat(confirmed.expiredAt(now.plusSeconds(100_000))).isFalse();
    }

    @Test
    void onlyHeldReservationsChangeState() {
        Reservation committed = held.commit(now);
        assertThat(committed.status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThatThrownBy(() -> committed.release(now))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.reservation-not-held"));
        assertThatThrownBy(() -> held.expire(now).commit(now)).isInstanceOf(InventoryStateConflict.class);
    }

    @Test
    void groupsShipAndReleaseOnTheirOwn() {
        Reservation r = held.withLines(List.of(
                new ReservationLine(1, "RICE", "HOU-FC", 2, List.of(), "l1", null, "org_retail"),
                new ReservationLine(2, "OIL", "HOU-FC", 1, List.of(), "l2", null, "org_retail")));
        Reservation partly = r.releaseGroups(Set.of("l2"), now);
        assertThat(partly.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(partly.heldLines(null)).extracting(ReservationLine::group).containsExactly("l1");
        assertThat(partly.releaseGroups(Set.of("l2"), now)).isSameAs(partly);

        Reservation done = partly.commitGroups(Set.of("l1"), now);
        assertThat(done.status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(done.committedLines()).extracting(ReservationLine::group).containsExactly("l1");
        assertThatThrownBy(() -> done.releaseGroups(Set.of("l1"), now))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.group-committed"));
        assertThat(r.unknownGroups(Set.of("l1", "x"))).containsExactly("x");
    }

    @Test
    void releasingTheRestAfterAShippedGroupEndsCommitted() {
        Reservation r = held.withLines(List.of(
                new ReservationLine(1, "RICE", "HOU-FC", 2, List.of(), "l1", null, null),
                new ReservationLine(2, "OIL", "HOU-FC", 1, List.of(), "l2", null, null)));
        Reservation rest = r.commitGroups(Set.of("l1"), now).release(now);
        assertThat(rest.status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(rest.lines()).extracting(ReservationLine::status)
                .containsExactly(ReservationLine.LineStatus.COMMITTED, ReservationLine.LineStatus.RELEASED);
    }
}
