package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
}
