package com.afrotransact.mcp.application;

/** Seller toolset: always scoped to the organisation in the caller's token. */
public interface SellerToolsService {

    ToolResult listMyProducts(McpCaller caller, String status, String query, Integer page);

    ToolResult productStatus(McpCaller caller, String productIdOrAtin);

    ToolResult listMyOrders(McpCaller caller, String status, Integer page);

    ToolResult updateShipmentStatus(McpCaller caller, String orderId, String shipmentId, String status, String carrier,
                                    String trackingNumber);

    ToolResult checkInventory(McpCaller caller, String sku, Boolean lowStockOnly);

    ToolResult requestCategory(McpCaller caller, String name, String parentCategoryId, String reason);
}
