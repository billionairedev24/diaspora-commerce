package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunPickLists.PickList;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStage;
import com.afrotransact.deliveryruns.domain.RunTerms;
import org.junit.jupiter.api.Test;

/** S264: a window's package mixes sellers, so each seller gets a pick list of its own lines per order. */
class PickListBuilderTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final UUID WINDOW = UUID.randomUUID();

    private static final Run RUN = Run.confirmedFromWindow(UUID.randomUUID(), UUID.randomUUID(), new RunTerms(
            new DeliveryWindow(Instant.parse("2026-10-24T14:00:00Z"), Instant.parse("2026-10-25T02:00:00Z")),
            Instant.parse("2026-10-21T01:00:00Z"), null, Instant.parse("2026-10-23T14:00:00Z"), 799, "USD", 1,
            Capacity.UNLIMITED, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 1), null, 3, 3, NOW);

    private static Location bastrop() {
        Location location = mock(Location.class);
        LocationDetails details = mock(LocationDetails.class);
        when(location.name()).thenReturn("Bastrop");
        when(location.details()).thenReturn(details);
        when(details.state()).thenReturn("TX");
        when(details.timeZone()).thenReturn(ZoneId.of("America/Chicago"));
        return location;
    }

    private static PreorderOrder order(String id, String number, PreorderOrderStatus status, PreorderLine... lines) {
        return new PreorderOrder(id, number, WINDOW, "cust-" + id, "Ada", "+1 806 555 0101", "ada@example.com",
                PreorderOrder.Address.NONE, null, NOW, AttributionChannel.SITE_DIRECT, null,
                List.of(new PreorderPackage("ship-" + id, "org-a", 2000, 0, List.of(lines), false)),
                PreorderFulfilment.DOOR, "USD", status, null, NOW, NOW, 0);
    }

    private static RunOrder slot(String orderId) {
        return RunOrder.reserve(UUID.randomUUID(), RUN.id(), orderId, "cust-" + orderId, 1, null, NOW).confirm(null, NOW);
    }

    @Test
    void eachSellerGetsItsOwnLinesPerOrderAndItsTotals() {
        PreorderOrder o1 = order("o1", "LBK-7-1001", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", "org-a", "Mama Tee's Pantry", 2),
                new PreorderLine("PAL", "Palm oil", "org-b", "Lagos Oils", 1));
        PreorderOrder o2 = order("o2", "LBK-7-1002", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", "org-a", "Mama Tee's Pantry", 3));
        PreorderOrder o3 = order("o3", "LBK-7-1003", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", "org-a", "Mama Tee's Pantry", 1),
                new PreorderLine("YAM", "Yam", "org-a", "Mama Tee's Pantry", 4));
        RunOrder s3 = slot("o3").advance(RunOrderStage.COLLECTED, NOW, NOW);

        List<PickList> lists = PickListBuilder.build(RUN, bastrop(), List.of(slot("o1"), slot("o2"), s3),
                Map.of("o1", o1, "o2", o2, "o3", o3));

        assertThat(lists).extracting(PickList::sellerOrgId).containsExactly("org-a", "org-b");
        PickList a = lists.get(0);
        assertThat(a.sellerName()).isEqualTo("Mama Tee's Pantry");
        assertThat(a.town()).isEqualTo("Bastrop");
        assertThat(a.state()).isEqualTo("TX");
        assertThat(a.timeZone()).isEqualTo("America/Chicago");
        assertThat(a.sellerPickupAt()).isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        assertThat(a.orders()).isEqualTo(3);
        assertThat(a.units()).isEqualTo(10);
        assertThat(a.collectedOrders()).isEqualTo(1);
        assertThat(a.items()).extracting(RunPickLists.Item::atin, RunPickLists.Item::units)
                .containsExactly(tuple("EGU", 6), tuple("YAM", 4));
        assertThat(a.bags()).extracting(RunPickLists.OrderBag::orderNumber)
                .containsExactly("LBK-7-1001", "LBK-7-1002", "LBK-7-1003");
        assertThat(a.bags().get(0).lines()).singleElement().satisfies(l -> {
            assertThat(l.atin()).isEqualTo("EGU");
            assertThat(l.units()).isEqualTo(2);
        });
        assertThat(a.bags().get(2).stage()).isEqualTo(RunOrderStage.COLLECTED);
        assertThat(a.bags().get(2).units()).isEqualTo(5);

        PickList b = lists.get(1);
        assertThat(b.orders()).isEqualTo(1);
        assertThat(b.units()).isEqualTo(1);
        assertThat(b.bags()).singleElement().satisfies(bag -> assertThat(bag.orderNumber()).isEqualTo("LBK-7-1001"));
    }

    @Test
    void cancelledOrdersAndSlotsNeedNoPacking() {
        PreorderOrder gone = order("g", "LBK-7-1004", PreorderOrderStatus.CANCELLED,
                new PreorderLine("EGU", "Egusi", "org-a", null, 5));
        PreorderOrder kept = order("k", "LBK-7-1005", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", "org-a", null, 1));
        PreorderOrder released = order("r", "LBK-7-1006", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", "org-a", null, 7));
        RunOrder cancelledSlot = slot("r").cancel(NOW);

        List<PickList> lists = PickListBuilder.build(RUN, bastrop(), List.of(slot("g"), slot("k"), cancelledSlot),
                Map.of("g", gone, "k", kept, "r", released));

        assertThat(lists).singleElement().satisfies(p -> {
            assertThat(p.units()).isEqualTo(1);
            assertThat(p.orders()).isEqualTo(1);
            assertThat(p.sellerName()).isNull();
        });
    }

    @Test
    void linesWithoutASellerAndSlotsWithoutAProjectedOrderAreLeftOut() {
        PreorderOrder o = order("o1", "LBK-7-1001", PreorderOrderStatus.PAID,
                new PreorderLine("EGU", "Egusi", null, null, 2), new PreorderLine("YAM", "Yam", "org-a", null, 1));
        List<PickList> lists = PickListBuilder.build(RUN, bastrop(), List.of(slot("o1"), slot("unknown")),
                Map.of("o1", o));
        assertThat(lists).singleElement().satisfies(p -> {
            assertThat(p.sellerOrgId()).isEqualTo("org-a");
            assertThat(p.units()).isEqualTo(1);
        });
        assertThat(PickListBuilder.build(RUN, bastrop(), List.of(), Map.of())).isEmpty();
    }
}
