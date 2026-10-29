package com.afrotransact.mcp.application;

/** Staff toolset: review queues and payment views. Personal data in application details is masked. */
public interface StaffToolsService {

    ToolResult applicationsQueue(McpCaller caller, String status, Integer page);

    ToolResult applicationDetail(McpCaller caller, String applicationId);

    ToolResult moderationQueue(McpCaller caller, Integer page);

    ToolResult orderPaymentSplit(McpCaller caller, String orderId);

    /** One order with its shipments and payment state; personal data masked. */
    ToolResult orderLookup(McpCaller caller, String orderId);

    /** Payout balance of a seller or provider: available, held orders with release dates, pauses. */
    ToolResult sellerPayoutStatus(McpCaller caller, String orgId);

    /** What Stripe still needs from a seller or provider, and whether payouts are paused. */
    ToolResult sellerAccountHealth(McpCaller caller, String orgId);
}
