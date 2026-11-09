package com.afrotransact.mcp.application;

/** Read-only tax tools of the staff toolset (permission tax:read). They change nothing; staff decide in the console. */
public interface TaxToolsService {

    /** Variants waiting for tax review (default), or the decisions already made (APPROVED, REJECTED, STALE). */
    ToolResult reviewQueue(McpCaller caller, String status, String query, Integer page);

    /** The shadow report over the last {@code days} days (7, 30 or 90; default 30), optionally for one provider. */
    ToolResult shadowReport(McpCaller caller, Integer days, String provider);
}
