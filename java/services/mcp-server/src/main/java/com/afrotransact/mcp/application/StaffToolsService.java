package com.afrotransact.mcp.application;

/** Staff toolset: review queues and payment views. Personal data in application details is masked. */
public interface StaffToolsService {

    ToolResult applicationsQueue(McpCaller caller, String status, Integer page);

    ToolResult applicationDetail(McpCaller caller, String applicationId);

    ToolResult moderationQueue(McpCaller caller, Integer page);

    ToolResult orderPaymentSplit(McpCaller caller, String orderId);
}
