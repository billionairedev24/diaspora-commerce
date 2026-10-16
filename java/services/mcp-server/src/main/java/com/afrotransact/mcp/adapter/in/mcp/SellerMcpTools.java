package com.afrotransact.mcp.adapter.in.mcp;

import com.afrotransact.mcp.application.SellerToolsService;
import com.afrotransact.mcp.application.ToolCatalog;
import com.afrotransact.mcp.application.ToolResult;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Seller toolset (scope {@code mcp:seller} and an organisation in the token). */
@Component
class SellerMcpTools {

    private final SellerToolsService seller;

    SellerMcpTools(SellerToolsService seller) {
        this.seller = seller;
    }

    @McpTool(name = ToolCatalog.LIST_MY_PRODUCTS, title = "List my products",
            description = "Products of the caller's organisation with their review status (catalog:write).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult listMyProducts(McpTransportContext context,
                              @McpToolParam(description = "IN_REVIEW, PENDING_CATEGORY_APPROVAL, LIVE, REJECTED or "
                                      + "PAUSED", required = false) String status,
                              @McpToolParam(description = "Text filter on title", required = false) String query,
                              @McpToolParam(description = "Page number from 0", required = false) Integer page) {
        return seller.listMyProducts(McpCallerExtractor.caller(context), status, query, page);
    }

    @McpTool(name = ToolCatalog.PRODUCT_STATUS, title = "Product status",
            description = "Review status (and rejection reason) of one of the organisation's products.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult productStatus(McpTransportContext context,
                             @McpToolParam(description = "Product id (UUID) or ATIN") String productIdOrAtin) {
        return seller.productStatus(McpCallerExtractor.caller(context), productIdOrAtin);
    }

    @McpTool(name = ToolCatalog.LIST_MY_ORDERS, title = "List my orders",
            description = "The organisation's shipments, newest first (orders:read). Each has its status, delivery "
                    + "method, tracking and the steps the seller may take next. Page with the returned nextCursor.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult listMyOrders(McpTransportContext context,
                            @McpToolParam(description = "Shipment status filter, e.g. placed, confirmed, packed, ready",
                                    required = false) String status,
                            @McpToolParam(description = "nextCursor of the previous page", required = false)
                            String cursor) {
        return seller.listMyOrders(McpCallerExtractor.caller(context), status, cursor);
    }

    @McpTool(name = ToolCatalog.UPDATE_SHIPMENT_STATUS, title = "Update shipment status",
            description = "Move a seller-fulfilled shipment one step forward: CONFIRMED, PACKED, READY, then SHIPPED "
                    + "(carrier shipments: needs a carrier and tracking number unless a label is already bought) or "
                    + "DELIVERED (pickup orders only: needs the buyer's 4-digit code from their order page). "
                    + "Cancellations, refunds, AfroTransact Delivery and AfroTransact-fulfilled shipments are not "
                    + "allowed here (orders:update-status).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    ToolResult updateShipmentStatus(McpTransportContext context,
                                    @McpToolParam(description = "Order number or id") String orderId,
                                    @McpToolParam(description = "Shipment id") String shipmentId,
                                    @McpToolParam(description = "CONFIRMED, PACKED, READY, SHIPPED or DELIVERED")
                                    String status,
                                    @McpToolParam(description = "Carrier code, e.g. USPS (SHIPPED)", required = false)
                                    String carrier,
                                    @McpToolParam(description = "Tracking number (SHIPPED)", required = false)
                                    String trackingNumber,
                                    @McpToolParam(description = "The buyer's 4-digit code (DELIVERED, pickup orders)",
                                            required = false) String handoverCode) {
        return seller.updateShipmentStatus(McpCallerExtractor.caller(context), orderId, shipmentId, status, carrier,
                trackingNumber, handoverCode);
    }

    @McpTool(name = ToolCatalog.CHECK_INVENTORY, title = "Check inventory",
            description = "Stock positions of the organisation's SKUs (on hand, reserved, available) per node "
                    + "(inventory:read).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult checkInventory(McpTransportContext context,
                              @McpToolParam(description = "SKU filter", required = false) String sku,
                              @McpToolParam(description = "Only positions with 10 or fewer available",
                                      required = false) Boolean lowStockOnly) {
        return seller.checkInventory(McpCallerExtractor.caller(context), sku, lowStockOnly);
    }

    @McpTool(name = ToolCatalog.REQUEST_CATEGORY, title = "Request a category",
            description = "Ask the catalogue team for a missing category; products can be built against it while it "
                    + "is reviewed (catalog:write).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    ToolResult requestCategory(McpTransportContext context,
                               @McpToolParam(description = "Category name") String name,
                               @McpToolParam(description = "Parent category id", required = false)
                               String parentCategoryId,
                               @McpToolParam(description = "Why it is needed", required = false) String reason) {
        return seller.requestCategory(McpCallerExtractor.caller(context), name, parentCategoryId, reason);
    }
}
