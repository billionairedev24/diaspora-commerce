package com.afrotransact.deliveryruns.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.DeliveryKind;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderMoney;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import org.springframework.stereotype.Component;

/**
 * The slots of runs with what each order paid (S265). The amounts are the ones recorded on the slot; a slot without
 * them (a run handed over from a pre-order window before they were recorded, or an orders-service that did not send
 * them) takes the fee, total and way of delivery from the window's order while that is still kept.
 */
@Component
class RunLedger {

    private final RunOrderRepository runOrders;
    private final PreorderOrderRepository preorders;

    RunLedger(RunOrderRepository runOrders, PreorderOrderRepository preorders) {
        this.runOrders = runOrders;
        this.preorders = preorders;
    }

    /** The slots of one run, whatever their status. */
    List<RunOrder> slots(UUID runId) {
        return slots(List.of(runId)).getOrDefault(runId, List.of());
    }

    /** The slots of each run (runs without slots map to an empty list). */
    Map<UUID, List<RunOrder>> slots(Collection<UUID> runIds) {
        List<RunOrder> all = runOrders.findByRuns(runIds);
        List<String> unknown = all.stream().filter(o -> !o.money().feeKnown()).map(RunOrder::orderId).toList();
        Map<String, PreorderOrder> projected = unknown.isEmpty() ? Map.of()
                : preorders.findByIds(unknown).stream().collect(Collectors.toMap(PreorderOrder::orderId,
                Function.identity(), (a, b) -> a));
        Map<UUID, List<RunOrder>> byRun = new LinkedHashMap<>();
        runIds.forEach(id -> byRun.put(id, new ArrayList<>()));
        for (RunOrder o : all) {
            PreorderOrder p = projected.get(o.orderId());
            byRun.computeIfAbsent(o.runId(), k -> new ArrayList<>()).add(p == null ? o : fill(o, p));
        }
        return byRun.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> List.copyOf(e.getValue()), (a, b) -> a, LinkedHashMap::new));
    }

    private static RunOrder fill(RunOrder slot, PreorderOrder p) {
        RunOrderMoney m = slot.money();
        DeliveryKind kind = p.fulfilment() == PreorderFulfilment.PICKUP ? DeliveryKind.PICKUP : DeliveryKind.DOOR;
        RunOrderMoney filled = new RunOrderMoney(p.deliveryFeeMinor(), p.totalMinor(), kind, m.commissionMinor(),
                m.refundedMinor(), m.refundedFeeMinor(), m.refundedAt());
        return slot.withMoney(filled, slot.updatedAt());
    }
}
