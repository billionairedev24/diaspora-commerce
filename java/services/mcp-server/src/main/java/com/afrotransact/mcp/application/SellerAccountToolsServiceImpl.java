package com.afrotransact.mcp.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import org.springframework.stereotype.Service;

@Service
class SellerAccountToolsServiceImpl implements SellerAccountToolsService {

    static final String PAYOUT_RULES = "Seller money is held for 7 days after delivery (or service completion): each "
            + "onHold entry has the order and the releaseAt date (null until delivered). After that it becomes "
            + "available within minutes, and the seller requests a payout (minimum payout applies, limited requests "
            + "a day); the bank usually receives it in a few business days. payoutsPaused means payouts are on hold "
            + "until the reason is fixed (see account_health). Never state a date that is not in the data.";

    private static final Set<String> ORDER_KEYS = Set.of("id", "number", "orderNumber", "status", "createdAt",
            "placedAt");
    private static final Set<String> SHIPMENT_KEYS = Set.of("id", "status", "fulfilmentType", "deliveryMethod",
            "carrier", "trackingNumber", "lines", "totalMinor", "currency", "nextActions", "deliveredAt");
    private static final Set<String> LINE_KEYS = Set.of("title", "sku", "atin", "quantity", "unitPriceMinor",
            "lineTotalMinor");

    private final ToolGuardService guard;
    private final PaymentsApi payments;
    private final SellerApi sellers;
    private final OrdersApi orders;

    SellerAccountToolsServiceImpl(ToolGuardService guard, PaymentsApi payments, SellerApi sellers, OrdersApi orders) {
        this.guard = guard;
        this.payments = payments;
        this.sellers = sellers;
        this.orders = orders;
    }

    @Override
    public ToolResult payoutBalance(McpCaller caller) {
        guard.require(caller.profile(), ToolCatalog.PAYOUT_BALANCE);
        Downstream d = payments.payoutBalance(caller.profile().orgId(), caller.bearerToken());
        return d.ok() ? new ToolResult("OK", d.body(), PAYOUT_RULES) : Results.from(d, "payout balance");
    }

    @Override
    public ToolResult listPayouts(McpCaller caller) {
        guard.require(caller.profile(), ToolCatalog.LIST_PAYOUTS);
        return Results.from(payments.payoutRequests(caller.profile().orgId(), 20, caller.bearerToken()), "payout list");
    }

    @Override
    public ToolResult accountHealth(McpCaller caller) {
        guard.require(caller.profile(), ToolCatalog.ACCOUNT_HEALTH);
        return Results.from(sellers.accountHealth(caller.profile().orgId(), caller.bearerToken()), "account health");
    }

    @Override
    public ToolResult getMyOrder(McpCaller caller, String orderId) {
        guard.require(caller.profile(), ToolCatalog.GET_MY_ORDER);
        if (orderId == null || orderId.isBlank()) {
            return ToolResult.of("REJECTED", "orderId is required");
        }
        Downstream d = orders.order(orderId.trim(), caller.bearerToken());
        if (!d.ok()) {
            return Results.from(d, "order");
        }
        Map<String, Object> order = Results.asMap(d.body());
        List<Object> mine = new ArrayList<>();
        if (order.get("shipments") instanceof List<?> shipments) {
            for (Object s : shipments) {
                Map<String, Object> shipment = Results.asMap(s);
                if (Objects.equals(caller.profile().orgId(), Results.text(shipment, "sellerOrgId"))
                        || caller.profile().can("*")) {
                    mine.add(shipmentView(shipment));
                }
            }
        }
        if (mine.isEmpty()) {
            return ToolResult.of("NOT_FOUND", "No shipment of your organisation on order " + orderId);
        }
        Map<String, Object> view = pick(order, ORDER_KEYS);
        view.put("shipments", mine);
        return new ToolResult("OK", view, "Only your organisation's shipments are shown, without buyer details.");
    }

    private static Map<String, Object> shipmentView(Map<String, Object> shipment) {
        Map<String, Object> view = pick(shipment, SHIPMENT_KEYS);
        if (shipment.get("lines") instanceof List<?> lines) {
            List<Object> clean = new ArrayList<>();
            for (Object l : lines) {
                clean.add(pick(Results.asMap(l), LINE_KEYS));
            }
            view.put("lines", clean);
        }
        return view;
    }

    private static Map<String, Object> pick(Map<String, Object> source, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> {
            if (keys.contains(k)) {
                out.put(k, v);
            }
        });
        return out;
    }
}
