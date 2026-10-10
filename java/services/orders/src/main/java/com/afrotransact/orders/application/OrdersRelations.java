package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.RelationshipTuple;

/**
 * OpenFGA relations checked by orders use cases ({@code contracts/openfga/model.fga}, S248). The order's market (its
 * ship-to country) and a shipment's order and seller organisation come from orders' database and are passed as
 * contextual tuples, so nothing has to be kept in sync with the relationship store.
 */
public final class OrdersRelations {

    /** Model type of a customer order. */
    public static final String ORDER_TYPE = "order";
    /** Model type of a seller's part of an order (one shipment). */
    public static final String SHIPMENT_TYPE = "shipment";
    /** On {@code order}: staff of the order's market (support, operations, finance) and first-party services. */
    public static final String CAN_VIEW = "can_view";
    /** On {@code shipment}: fulfilment / support members of the seller, operations staff of the market, services. */
    public static final String CAN_UPDATE_STATUS = "can_update_status";
    /** On {@code organization}: a seller's shipment queue and analytics (members, market staff, services). */
    public static final String ORG_VIEW = "can_view";
    /** On {@code platform}: every organisation's orders (platform-wide staff, first-party services). */
    public static final String PLATFORM_VIEW_ALL = "can_view_all";

    private static final Pattern MARKET = Pattern.compile("[A-Z]{2}");

    private OrdersRelations() {
    }

    static FgaObject order(Order order) {
        return FgaObject.of(ORDER_TYPE, order.id());
    }

    static FgaObject shipment(Shipment shipment) {
        return FgaObject.of(SHIPMENT_TYPE, shipment.id());
    }

    /**
     * {@code order#market} from the ship-to country (content's market codes: ISO 3166-1 alpha-2, UK → GB) and the
     * market's link to the platform, so platform-wide staff reach orders of any market.
     */
    static List<RelationshipTuple> orderContext(Order order) {
        List<RelationshipTuple> tuples = new ArrayList<>();
        String market = marketOf(order);
        if (market != null) {
            FgaObject m = FgaObject.of("market", market);
            tuples.add(RelationshipTuple.of(m, "market", order(order)));
            tuples.add(RelationshipTuple.of(FgaObject.PLATFORM, "platform", m));
        }
        return tuples;
    }

    /** The order context plus {@code shipment#order} and {@code shipment#org} (the shipment's seller). */
    static List<RelationshipTuple> shipmentContext(Order order, Shipment shipment) {
        List<RelationshipTuple> tuples = new ArrayList<>(orderContext(order));
        tuples.add(RelationshipTuple.of(order(order), "order", shipment(shipment)));
        tuples.add(RelationshipTuple.ownedBy(shipment(shipment), shipment.sellerOrgId()));
        return tuples;
    }

    static String marketOf(Order order) {
        if (order.shipTo() == null || order.shipTo().country() == null) {
            return null;
        }
        String c = order.shipTo().country().trim().toUpperCase(Locale.ROOT);
        c = "UK".equals(c) ? "GB" : c;
        return MARKET.matcher(c).matches() ? c : null;
    }
}
