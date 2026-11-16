package com.afrotransact.inventory.adapter.in.web;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the stock endpoints the MCP tools read, for the in-process {@code InventoryUserApi}: each method calls the controller
 * handler of the HTTP endpoint (same use case, same response record) and turns the response into a JSON tree. It lives next to the
 * controller because its handlers and response records are package private; who may see what is decided by the use case.
 */
@Component
public class InventoryEndpointViews {

    /** The page size the MCP tools have always asked {@code GET /v1/positions} for. */
    private static final int POSITIONS_SIZE = 50;

    private final StockController stock;
    private final JsonMapper json;

    InventoryEndpointViews(StockController stock, JsonMapper json) {
        this.stock = stock;
        this.json = json;
    }

    public Object availability(List<String> skus) {
        return json.convertValue(stock.availability(skus), Object.class);
    }

    public Object positions(String ownerOrg, String sku, boolean lowStock) {
        return json.convertValue(stock.positions(sku, null, ownerOrg, lowStock, 0, POSITIONS_SIZE), Object.class);
    }
}
