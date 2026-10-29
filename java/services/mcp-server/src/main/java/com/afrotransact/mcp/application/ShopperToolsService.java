package com.afrotransact.mcp.application;

import java.util.List;

/**
 * Shopper toolset. Reads go to the public APIs under the shopper's token; anything that would change a cart, an order
 * or spend money is returned as a proposal the agent must show to the shopper for confirmation.
 */
public interface ShopperToolsService {

    ToolResult searchProducts(McpCaller caller, String query, Long maxPriceMinor, Boolean inStockOnly, String community,
                              Integer size);

    ToolResult getProduct(McpCaller caller, String atin);

    ToolResult checkDelivery(McpCaller caller, String atin, String zip);

    ToolResult getCart(McpCaller caller);

    ToolResult proposeAddToCart(McpCaller caller, String atin, Integer quantity);

    ToolResult trackOrder(McpCaller caller, String orderId);

    ToolResult myOrderHistory(McpCaller caller, String cursor);

    ToolResult startReturnProposal(McpCaller caller, String orderId, String reason, List<String> items);

    ToolResult findService(McpCaller caller, String query, String zip);
}
