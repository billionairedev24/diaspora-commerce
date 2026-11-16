package com.afrotransact.orders.adapter.in.web;

import java.util.Map;

import com.afrotransact.orders.adapter.in.web.OrdersDtos.TrackingRequest;
import com.afrotransact.orders.adapter.in.web.OrdersDtos.TransitionRequest;
import com.afrotransact.platform.web.error.ModuleFailures;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the order and shipment endpoints the MCP tools use, for the in-process {@code OrdersUserApi}: each method calls the
 * controller handler of the HTTP endpoint (same use case, same response, so the same JSON as the HTTP answer) and turns the response
 * into a JSON tree. It lives next to the controllers because their handlers and request records are package private; who may do what is
 * decided by the use cases, as for a token. The request-body validation of {@code @Valid} is applied by hand on the two writes.
 */
@Component
public class OrdersEndpointViews {

    private final OrderController orders;
    private final ShipmentController shipments;
    private final Validator validator;
    private final JsonMapper json;

    OrdersEndpointViews(OrderController orders, ShipmentController shipments, Validator validator, JsonMapper json) {
        this.orders = orders;
        this.shipments = shipments;
        this.validator = validator;
        this.json = json;
    }

    public Object order(String orderId) {
        return tree(orders.get(orderId));
    }

    public Object myOrders(String cursor, int limit) {
        return tree(orders.list("me", cursor, limit));
    }

    public Object sellerShipments(String status, String cursor, int limit) {
        return tree(shipments.sellerQueue(status, null, cursor, limit));
    }

    public Object paymentSplit(String orderNumber) {
        return tree(orders.split(orderNumber));
    }

    public Object transitionShipment(String shipmentId, Map<String, Object> body) {
        TransitionRequest request = new TransitionRequest(text(body, "to"), text(body, "reason"), text(body, "handoverCode"));
        ModuleFailures.validate(validator, request);
        return tree(shipments.transition(shipmentId, null, request));
    }

    public Object attachTracking(String shipmentId, String carrier, String trackingNumber) {
        TrackingRequest request = new TrackingRequest(carrier, trackingNumber);
        ModuleFailures.validate(validator, request);
        return tree(shipments.tracking(shipmentId, request));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Object tree(Object response) {
        return json.convertValue(response, Object.class);
    }
}
