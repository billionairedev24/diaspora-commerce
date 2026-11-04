package com.afrotransact.mcp.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.mcp.domain.McpRuleViolation;
import com.afrotransact.mcp.domain.ShipmentStatusPolicy;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import org.springframework.stereotype.Service;

@Service
class SellerToolsServiceImpl implements SellerToolsService {

    private static final Set<String> PRODUCT_STATUSES = Set.of("IN_REVIEW", "PENDING_CATEGORY_APPROVAL", "LIVE",
            "REJECTED", "PAUSED");

    private final ToolGuardService guard;
    private final CatalogApi catalog;
    private final OrdersApi orders;
    private final InventoryApi inventory;
    private final ShipmentStatusPolicy shipments;

    SellerToolsServiceImpl(ToolGuardService guard, CatalogApi catalog, OrdersApi orders, InventoryApi inventory,
                           ShipmentStatusPolicy shipments) {
        this.guard = guard;
        this.catalog = catalog;
        this.orders = orders;
        this.inventory = inventory;
        this.shipments = shipments;
    }

    @Override
    public ToolResult listMyProducts(McpCaller caller, String status, String query, Integer page) {
        guard.require(caller.profile(), ToolCatalog.LIST_MY_PRODUCTS);
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (s != null && !PRODUCT_STATUSES.contains(s)) {
            return ToolResult.of("REJECTED", "status must be one of " + PRODUCT_STATUSES);
        }
        return Results.from(catalog.products(caller.profile().orgId(), s, query, page(page), 25,
                caller.bearerToken()), "product list");
    }

    @Override
    public ToolResult productStatus(McpCaller caller, String productIdOrAtin) {
        guard.require(caller.profile(), ToolCatalog.PRODUCT_STATUS);
        Downstream d = catalog.product(productIdOrAtin == null ? "" : productIdOrAtin.trim(), caller.bearerToken());
        if (d.ok() && !ownedByCaller(caller, Results.asMap(d.body()))) {
            return ToolResult.of("NOT_FOUND", "No product " + productIdOrAtin + " in your organisation");
        }
        if (!d.ok()) {
            return Results.from(d, "product");
        }
        Map<String, Object> p = Results.asMap(d.body());
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("id", "atin", "title", "status", "rejectionReason", "note", "updatedAt")) {
            if (p.containsKey(k)) {
                data.put(k, p.get(k));
            }
        }
        return ToolResult.ok(data);
    }

    @Override
    public ToolResult listMyOrders(McpCaller caller, String status, String cursor) {
        guard.require(caller.profile(), ToolCatalog.LIST_MY_ORDERS);
        return Results.from(orders.sellerShipments(status == null || status.isBlank() ? null
                : status.trim().toLowerCase(Locale.ROOT), cursor, 25, caller.bearerToken()), "order list");
    }

    @Override
    public ToolResult updateShipmentStatus(McpCaller caller, String orderId, String shipmentId, String status,
                                           String carrier, String trackingNumber, String handoverCode) {
        guard.require(caller.profile(), ToolCatalog.UPDATE_SHIPMENT_STATUS);
        Downstream order = orders.order(orderId, caller.bearerToken());
        if (!order.ok()) {
            return Results.from(order, "order");
        }
        Map<String, Object> shipment = findShipment(Results.asMap(order.body()), shipmentId);
        if (shipment.isEmpty() || !Objects.equals(caller.profile().orgId(), Results.text(shipment, "sellerOrgId"))
                && !caller.profile().can("*")) {
            return ToolResult.of("NOT_FOUND", "No shipment " + shipmentId + " of your organisation on order " + orderId);
        }
        // Tracking given now, else the carrier label already on the shipment (bought in Seller Center).
        boolean givenTracking = carrier != null && !carrier.isBlank() && trackingNumber != null
                && !trackingNumber.isBlank();
        String useCarrier = givenTracking ? carrier.trim() : Results.text(shipment, "carrier");
        String useTracking = givenTracking ? trackingNumber.trim() : Results.text(shipment, "trackingNumber");
        try {
            shipments.check(Results.text(shipment, "fulfilmentType"), Results.text(shipment, "deliveryMethod"),
                    Results.text(shipment, "status"), status, useCarrier, useTracking, handoverCode);
        } catch (McpRuleViolation e) {
            return new ToolResult("REJECTED", Map.of("code", e.code()), e.getMessage());
        }
        String target = status.trim().toUpperCase(Locale.ROOT);
        if ("SHIPPED".equals(target) && givenTracking && !useTracking.equals(Results.text(shipment, "trackingNumber"))) {
            Downstream tracked = orders.attachTracking(shipmentId, useCarrier.toLowerCase(Locale.ROOT), useTracking,
                    caller.bearerToken());
            if (!tracked.ok()) {
                return Results.from(tracked, "shipment tracking");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", "SHIPPED".equals(target) ? "picked_up" : target.toLowerCase(Locale.ROOT));
        if ("DELIVERED".equals(target)) {
            body.put("handoverCode", handoverCode.trim());
        }
        return Results.from(orders.transitionShipment(shipmentId, body, caller.bearerToken()), "shipment update");
    }

    @Override
    public ToolResult checkInventory(McpCaller caller, String sku, Boolean lowStockOnly) {
        guard.require(caller.profile(), ToolCatalog.CHECK_INVENTORY);
        return Results.from(inventory.positions(caller.profile().orgId(), sku == null || sku.isBlank() ? null
                : sku.trim().toUpperCase(Locale.ROOT), Boolean.TRUE.equals(lowStockOnly), caller.bearerToken()),
                "inventory");
    }

    @Override
    public ToolResult requestCategory(McpCaller caller, String name, String parentCategoryId, String reason) {
        guard.require(caller.profile(), ToolCatalog.REQUEST_CATEGORY);
        if (name == null || name.isBlank() || name.length() > 120) {
            return ToolResult.of("REJECTED", "name is required (max 120 characters)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        if (parentCategoryId != null && !parentCategoryId.isBlank()) {
            body.put("parentId", parentCategoryId.trim());
        }
        body.put("reason", reason == null ? "" : reason.trim());
        return Results.from(catalog.requestCategory(body, caller.bearerToken()), "category request");
    }

    private static boolean ownedByCaller(McpCaller caller, Map<String, Object> product) {
        if (caller.profile().can("*")) {
            return true;
        }
        String owner = Results.text(product, "ownerOrgId");
        if (owner.isEmpty()) {
            owner = Results.text(product, "sellerOrgId");
        }
        return owner.equals(caller.profile().orgId());
    }

    private static Map<String, Object> findShipment(Map<String, Object> order, String shipmentId) {
        Object list = order.get("shipments");
        if (list instanceof List<?> shipmentsList) {
            for (Object s : shipmentsList) {
                Map<String, Object> m = Results.asMap(s);
                if (shipmentId != null && shipmentId.equals(Results.text(m, "id"))) {
                    return m;
                }
            }
        }
        return Map.of();
    }

    private static int page(Integer page) {
        return page == null ? 0 : Math.max(0, page);
    }
}
