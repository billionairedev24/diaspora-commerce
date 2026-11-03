package com.afrotransact.mcp.application;

/** Seller toolset, money and account: when the seller is paid, why a payout is late, one of their orders. */
public interface SellerAccountToolsService {

    /** Available now, held orders with the date each is released, in transit, paid, minimum and pauses. */
    ToolResult payoutBalance(McpCaller caller);

    ToolResult listPayouts(McpCaller caller);

    /** What Stripe still needs and whether payouts or charges are switched off. */
    ToolResult accountHealth(McpCaller caller);

    /** One order, limited to the caller's own shipments and without buyer personal data. */
    ToolResult getMyOrder(McpCaller caller, String orderId);
}
