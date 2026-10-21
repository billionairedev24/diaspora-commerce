package com.afrotransact.mcp.adapter.in.mcp;

import java.util.List;

import com.afrotransact.mcp.application.ShopperToolsService;
import com.afrotransact.mcp.application.ToolCatalog;
import com.afrotransact.mcp.application.ToolResult;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Shopper toolset (scope {@code mcp:shopper}). Write-like tools only return proposals for the user to confirm. */
@Component
class ShopperMcpTools {

    private final ShopperToolsService shopper;

    ShopperMcpTools(ShopperToolsService shopper) {
        this.shopper = shopper;
    }

    @McpTool(name = ToolCatalog.SEARCH_PRODUCTS, title = "Search products",
            description = "Search the AfroTransact catalogue (diaspora groceries, spices, beauty, fashion, gifts). "
                    + "Returns hits with ATIN, title, price in minor units, stock and delivery days. Only present "
                    + "products returned here; never invent products or prices.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult searchProducts(McpTransportContext context,
                              @McpToolParam(description = "What to look for, e.g. 'egusi' or 'jollof seasoning'")
                              String query,
                              @McpToolParam(description = "Price ceiling in minor units (cents)", required = false)
                              Long maxPriceMinor,
                              @McpToolParam(description = "Only in-stock items (default true)", required = false)
                              Boolean inStockOnly,
                              @McpToolParam(description = "Community filter, e.g. Nigerian, Ghanaian, Indian",
                                      required = false) String community,
                              @McpToolParam(description = "Number of hits, 1-24 (default 10)", required = false)
                              Integer size) {
        return shopper.searchProducts(McpCallerExtractor.caller(context), query, maxPriceMinor, inStockOnly, community,
                size);
    }

    @McpTool(name = ToolCatalog.GET_PRODUCT, title = "Get product",
            description = "The live product page for an ATIN: title, attributes, images and variants with prices.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult getProduct(McpTransportContext context,
                          @McpToolParam(description = "Product ATIN, e.g. AT1B2C3D4E") String atin) {
        return shopper.getProduct(McpCallerExtractor.caller(context), atin);
    }

    @McpTool(name = ToolCatalog.CHECK_DELIVERY, title = "Check delivery",
            description = "Delivery promise for a product to a US ZIP code (e.g. 'Tomorrow, Oct 10'). Says when "
                    + "promises are not available instead of guessing.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult checkDelivery(McpTransportContext context,
                             @McpToolParam(description = "Product ATIN") String atin,
                             @McpToolParam(description = "5-digit US ZIP code") String zip) {
        return shopper.checkDelivery(McpCallerExtractor.caller(context), atin, zip);
    }

    @McpTool(name = ToolCatalog.GET_CART, title = "Get cart",
            description = "The user's cart: packages per seller, lines with live price and stock, saved-for-later.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult getCart(McpTransportContext context) {
        return shopper.getCart(McpCallerExtractor.caller(context));
    }

    @McpTool(name = ToolCatalog.PROPOSE_ADD_TO_CART, title = "Propose add to cart",
            description = "Prepare (do not perform) adding a product to the cart. Returns a PROPOSED action with the "
                    + "exact API call; show it to the user — only their confirmation adds the item.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult proposeAddToCart(McpTransportContext context,
                                @McpToolParam(description = "Variant or product ATIN returned by search_products")
                                String atin,
                                @McpToolParam(description = "Quantity 1-99 (default 1)", required = false)
                                Integer quantity) {
        return shopper.proposeAddToCart(McpCallerExtractor.caller(context), atin, quantity);
    }

    @McpTool(name = ToolCatalog.TRACK_ORDER, title = "Track order",
            description = "Status and shipments (carrier, tracking, promised date) of one of the user's orders.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult trackOrder(McpTransportContext context,
                          @McpToolParam(description = "Order number or id") String orderId) {
        return shopper.trackOrder(McpCallerExtractor.caller(context), orderId);
    }

    @McpTool(name = ToolCatalog.MY_ORDER_HISTORY, title = "My order history",
            description = "The user's recent orders, newest first, with status and totals. Use track_order for one "
                    + "order's shipments. Page with the returned nextCursor.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult myOrderHistory(McpTransportContext context,
                              @McpToolParam(description = "nextCursor of the previous page", required = false)
                              String cursor) {
        return shopper.myOrderHistory(McpCallerExtractor.caller(context), cursor);
    }

    @McpTool(name = ToolCatalog.START_RETURN_PROPOSAL, title = "Propose a return",
            description = "Prepare (do not perform) a return request for an order. Returns a PROPOSED action the user "
                    + "must confirm.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult startReturnProposal(McpTransportContext context,
                                   @McpToolParam(description = "Order number") String orderId,
                                   @McpToolParam(description = "DAMAGED, WRONG_ITEM, NOT_AS_DESCRIBED, EXPIRED, "
                                           + "NO_LONGER_NEEDED or OTHER") String reason,
                                   @McpToolParam(description = "ATINs to return; empty = whole order",
                                           required = false) List<String> items) {
        return shopper.startReturnProposal(McpCallerExtractor.caller(context), orderId, reason, items);
    }

    @McpTool(name = ToolCatalog.FIND_SERVICE, title = "Find a service",
            description = "Find bookable services and professionals (braiders, caterers, tailors, makeup artists) "
                    + "near a ZIP code.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult findService(McpTransportContext context,
                           @McpToolParam(description = "e.g. 'knotless braids' or 'Nigerian caterer'") String query,
                           @McpToolParam(description = "5-digit US ZIP code", required = false) String zip) {
        return shopper.findService(McpCallerExtractor.caller(context), query, zip);
    }
}
