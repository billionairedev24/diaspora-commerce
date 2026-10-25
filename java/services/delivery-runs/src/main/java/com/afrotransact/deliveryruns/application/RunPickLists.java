package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.RunOrderStage;

/** Value objects of the run pick lists (S264). */
public final class RunPickLists {

    private RunPickLists() {
    }

    /**
     * Units of one product.
     *
     * @param atin  variant ATIN
     * @param name  product title
     * @param units units to pick
     */
    public record Item(String atin, String name, int units) {
    }

    /**
     * What one run order needs from the seller: the order number to write on the bag, and its lines.
     *
     * @param orderId     orders-service order id
     * @param orderNumber window order number, e.g. {@code LBK-7-1001}
     * @param stage       where the order's package is on the run (the bag was collected once it is past PENDING)
     * @param units       units of this seller in the order
     * @param lines       the seller's lines
     */
    public record OrderBag(String orderId, String orderNumber, RunOrderStage stage, int units, List<Item> lines) {
    }

    /**
     * One seller's pick list for a run.
     *
     * @param runId          run
     * @param town           location name ("Bastrop")
     * @param state          state code
     * @param timeZone       IANA zone of the location
     * @param runStatus      run status
     * @param windowStart    delivery window start
     * @param windowEnd      delivery window end
     * @param sellerPickupAt when the driver collects from sellers (null if not scheduled)
     * @param dropMode       doorstep or pickup point
     * @param sellerOrgId    the seller
     * @param sellerName     the seller's name (null when unknown)
     * @param orders         orders with lines of this seller
     * @param units          units to pick in total
     * @param collectedOrders orders the driver has already collected
     * @param items          units per product across all orders, most units first
     * @param bags           per order, in order number order: what goes into each bag
     */
    public record PickList(UUID runId, String town, String state, String timeZone, String runStatus,
                           Instant windowStart, Instant windowEnd, Instant sellerPickupAt, DropMode dropMode,
                           String sellerOrgId, String sellerName, int orders, int units, int collectedOrders,
                           List<Item> items, List<OrderBag> bags) {
    }
}
