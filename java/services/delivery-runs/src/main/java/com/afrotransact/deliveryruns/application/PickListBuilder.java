package com.afrotransact.deliveryruns.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.deliveryruns.application.RunPickLists.Item;
import com.afrotransact.deliveryruns.application.RunPickLists.OrderBag;
import com.afrotransact.deliveryruns.application.RunPickLists.PickList;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStage;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunTerms;

/**
 * Splits a run's orders into one pick list per seller: a window's package mixes sellers, so each line goes to the
 * seller named on it. Only confirmed slots whose order still counts take part (a cancelled order needs no packing).
 */
final class PickListBuilder {

    private PickListBuilder() {
    }

    /**
     * @param orders the projected pre-orders of the slots, by order id (slots without one are skipped)
     * @return pick lists in the order of their first line, largest (most units) first
     */
    static List<PickList> build(Run run, Location location, List<RunOrder> slots, Map<String, PreorderOrder> orders) {
        Map<String, Seller> bySeller = new LinkedHashMap<>();
        for (RunOrder slot : slots) {
            PreorderOrder order = orders.get(slot.orderId());
            if (slot.status() != RunOrderStatus.CONFIRMED || order == null || !order.counts()) {
                continue;
            }
            Map<String, List<PreorderLine>> mine = new LinkedHashMap<>();
            for (PreorderLine l : order.lines()) {
                if (l.sellerOrgId() != null && !l.sellerOrgId().isBlank() && l.qty() > 0) {
                    mine.computeIfAbsent(l.sellerOrgId(), k -> new ArrayList<>()).add(l);
                }
            }
            for (Map.Entry<String, List<PreorderLine>> e : mine.entrySet()) {
                Seller seller = bySeller.computeIfAbsent(e.getKey(), k -> new Seller(e.getKey()));
                seller.add(slot, order, e.getValue());
            }
        }
        RunTerms t = run.terms();
        return bySeller.values().stream()
                .map(s -> s.toPickList(run, location, t))
                .sorted(Comparator.comparingInt(PickList::units).reversed().thenComparing(PickList::sellerOrgId))
                .toList();
    }

    /** One seller's lines, collected while the slots are walked. */
    private static final class Seller {

        private final String orgId;
        private String name;
        private final List<OrderBag> bags = new ArrayList<>();
        private final Map<String, int[]> units = new LinkedHashMap<>();
        private final Map<String, String> titles = new LinkedHashMap<>();
        private int collected;

        Seller(String orgId) {
            this.orgId = orgId;
        }

        void add(RunOrder slot, PreorderOrder order, List<PreorderLine> lines) {
            Map<String, int[]> perOrder = new LinkedHashMap<>();
            Map<String, String> orderTitles = new LinkedHashMap<>();
            int total = 0;
            for (PreorderLine l : lines) {
                String key = l.atin() == null ? l.name() : l.atin();
                perOrder.computeIfAbsent(key, k -> new int[1])[0] += l.qty();
                orderTitles.putIfAbsent(key, l.name());
                units.computeIfAbsent(key, k -> new int[1])[0] += l.qty();
                titles.putIfAbsent(key, l.name());
                total += l.qty();
                if (name == null && l.sellerName() != null && !l.sellerName().isBlank()) {
                    name = l.sellerName();
                }
            }
            bags.add(new OrderBag(order.orderId(), order.number(), slot.stage(), total,
                    items(perOrder, orderTitles)));
            if (slot.stage() != RunOrderStage.PENDING) {
                collected++;
            }
        }

        PickList toPickList(Run run, Location location, RunTerms t) {
            List<OrderBag> sorted = bags.stream().sorted(Comparator.comparing(OrderBag::orderNumber)).toList();
            int total = sorted.stream().mapToInt(OrderBag::units).sum();
            List<Item> items = items(units, titles).stream()
                    .sorted(Comparator.comparingInt(Item::units).reversed().thenComparing(Item::name)).toList();
            return new PickList(run.id(), location.name(), location.details().state(),
                    location.details().timeZone().getId(), run.status().name(), t.window().start(), t.window().end(),
                    t.sellerPickupAt(), t.dropMode(), orgId, name, sorted.size(), total, collected, items, sorted);
        }

        private static List<Item> items(Map<String, int[]> counts, Map<String, String> names) {
            return counts.entrySet().stream()
                    .map(e -> new Item(e.getKey(), names.get(e.getKey()), e.getValue()[0])).toList();
        }
    }
}
