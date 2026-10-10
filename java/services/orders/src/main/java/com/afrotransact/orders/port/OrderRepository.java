package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.StatusChange;

/** Orders, shipments, lines and the shipment status history. */
public interface OrderRepository {

    void insert(Order order);

    Optional<Order> findById(String orderId);

    Optional<Order> findByNumber(String number);

    /** Loads the order with {@code SELECT … FOR UPDATE}: transitions of one order's shipments are serialised. */
    Optional<Order> lockById(String orderId);

    Optional<String> orderIdOfShipment(String shipmentId);

    /** Shipment id of a carrier parcel. */
    Optional<String> shipmentByTracking(String carrier, String trackingNumber);

    /** Writes the order header and every shipment; optimistic version check on the order. */
    void update(Order order);

    /** True when this (shipment, event id) was already applied. */
    boolean seen(String shipmentId, String eventId);

    void appendHistory(StatusChange change, CourierProof proof);

    List<StatusChange> history(String orderId);

    Page<Order> byCustomer(String customerId, int offset, int limit);

    Page<SellerShipment> sellerShipments(String sellerOrgId, ShipmentStatus status, int offset, int limit);

    Page<Order> search(Search criteria, int offset, int limit);

    /** A shipment in a seller's queue with what the seller needs to ship it. */
    record SellerShipment(Shipment shipment, String orderNumber, OrderStatus orderStatus, ShipTo shipTo,
                          Instant placedAt, int shipmentCount) {
    }

    /** Staff search filters; null means "any". */
    record Search(String number, String customerId, String sellerOrgId, OrderStatus status,
                  ShipmentStatus shipmentStatus, Instant from, Instant to) {
    }
}
