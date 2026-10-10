package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PreorderDomainTest {

    private static final Instant CLOSES = Instant.parse("2026-10-20T05:00:00Z");

    private static PreorderPackage window() {
        return PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7", LocalDate.parse("2026-10-25"),
                CLOSES, PreorderFulfilment.DOOR);
    }

    @Test
    void cancellableUntilTheWindowCloses() {
        assertThat(window().cancellableAt(CLOSES.minusSeconds(1))).isTrue();
        assertThat(window().cancellableAt(CLOSES)).isFalse();
        assertThat(PreorderPackage.placed("w", null, null, LocalDate.now(), null, PreorderFulfilment.PICKUP)
                .cancellableAt(Instant.now())).as("no close time known").isTrue();
    }

    @Test
    void missedDeliveriesAreAnsweredOnceWithARefund() {
        assertThatThrownBy(() -> window().answered(MissedDeliveryChoice.WAIT, CLOSES))
                .isInstanceOf(OrdersStateConflict.class);
        PreorderPackage missed = window().missed(CLOSES);
        assertThat(missed.missed(CLOSES.plusSeconds(60)).deliveryMissedAt()).isEqualTo(CLOSES);
        PreorderPackage waiting = missed.answered(MissedDeliveryChoice.WAIT, CLOSES);
        assertThat(waiting.answered(MissedDeliveryChoice.WAIT, CLOSES)).isSameAs(waiting);
        PreorderPackage refund = waiting.answered(MissedDeliveryChoice.REFUND, CLOSES);
        assertThat(refund.missedChoice()).isEqualTo(MissedDeliveryChoice.REFUND);
        assertThatThrownBy(() -> refund.answered(MissedDeliveryChoice.WAIT, CLOSES))
                .isInstanceOf(OrdersStateConflict.class);
    }

    @Test
    void fulfilmentAndStageParsing() {
        assertThat(PreorderFulfilment.parse("door").method()).isEqualTo(DeliveryMethod.AFROTRANSACT_DELIVERY);
        assertThat(PreorderFulfilment.parse("PICKUP").method()).isEqualTo(DeliveryMethod.PICKUP);
        assertThatThrownBy(() -> PreorderFulfilment.parse("carrier")).isInstanceOf(OrdersRuleViolation.class);
        assertThat(PreorderStage.parse("open")).isEqualTo(PreorderStage.OPEN);
        assertThat(PreorderStage.parse("PAUSED")).isEqualTo(PreorderStage.UNAVAILABLE);
        assertThat(PreorderStage.parse(null)).isEqualTo(PreorderStage.UNAVAILABLE);
    }

    @Test
    void attributionMapsToChannels() {
        assertThat(OrderOrigin.checkout(new Attribution("ig", "STORY", "LBK-7")).channel())
                .isEqualTo(OrderChannel.INSTAGRAM);
        assertThat(OrderChannel.fromSource("wa")).isEqualTo(OrderChannel.WHATSAPP);
        assertThat(OrderChannel.fromSource("ribbon")).isEqualTo(OrderChannel.SITE_RIBBON);
        assertThat(OrderChannel.fromSource(null)).isEqualTo(OrderChannel.SITE_DIRECT);
        assertThat(Attribution.ofNullable(" ", null, "")).isNull();
        assertThat(OrderOrigin.checkout(null)).isEqualTo(OrderOrigin.none());
        OrderOrigin late = OrderOrigin.lateAdd("cs-1", "Chat", null, "LBK-7");
        assertThat(late.channel()).isEqualTo(OrderChannel.AGENT_LATE);
        assertThat(late.lateAdd()).isTrue();
        assertThat(late.attribution().po()).isEqualTo("LBK-7");
    }

    @Test
    void onlyPreorderShipmentsCarryAWindowAndTheyAreNotStockTracked() {
        assertThat(FulfilmentType.PREORDER.inventoryTracked()).isFalse();
        assertThat(FulfilmentType.PREORDER.afrotransactFulfilled()).isTrue();
        Shipment pre = Shipments.of(FulfilmentType.PREORDER, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.PLACED);
        assertThat(pre.preorderShipment()).isTrue();
        Shipment missed = pre.withPreorder(pre.preorder().missed(CLOSES));
        assertThat(missed.preorder().deliveryMissed()).isTrue();
        Shipment regular = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER,
                ShipmentStatus.PLACED);
        assertThatThrownBy(() -> regular.withPreorder(window())).isInstanceOf(OrdersRuleViolation.class);
        // a transition keeps the window snapshot
        assertThat(pre.transition(ShipmentStatus.CANCELLED, new TransitionActor(ActorKind.CUSTOMER, "c", null),
                "changed my mind", TransitionSource.API, "e1", Instant.now(), null,
                new DefaultShipmentTransitionPolicy()).shipment().preorder()).isEqualTo(pre.preorder());
    }
}
