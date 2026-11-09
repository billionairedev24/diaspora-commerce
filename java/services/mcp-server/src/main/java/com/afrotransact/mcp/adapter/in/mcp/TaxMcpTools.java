package com.afrotransact.mcp.adapter.in.mcp;

import com.afrotransact.mcp.application.TaxToolsService;
import com.afrotransact.mcp.application.ToolCatalog;
import com.afrotransact.mcp.application.ToolResult;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Read-only category tax tools of the staff toolset (scope {@code mcp:staff}, permission {@code tax:read}). */
@Component
class TaxMcpTools {

    private final TaxToolsService tax;

    TaxMcpTools(TaxToolsService tax) {
        this.tax = tax;
    }

    @McpTool(name = ToolCatalog.TAX_REVIEW_QUEUE, title = "Tax review queue",
            description = "Product variants waiting for tax review (default), or the decisions already made with who "
                    + "decided and when (tax:read). Each record has the product, its category, the facts the seller "
                    + "entered, the proof attached, the proposed tax decision and the rule version. Read only: staff "
                    + "approve, move or send back in the staff console under Fees & commissions > Sales tax > Tax review.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult taxReviewQueue(McpTransportContext context,
                              @McpToolParam(description = "PENDING_REVIEW (default), APPROVED, REJECTED or STALE",
                                      required = false) String status,
                              @McpToolParam(description = "Product title, ATIN or GTIN", required = false) String query,
                              @McpToolParam(description = "Page number from 0", required = false) Integer page) {
        return tax.reviewQueue(McpCallerExtractor.caller(context), status, query, page);
    }

    @McpTool(name = ToolCatalog.TAX_SHADOW_REPORT, title = "Tax shadow report",
            description = "A payment provider's tax quote against ours on real orders (never charged) over the last 7, "
                    + "30 or 90 days: lines compared, lines that differ, differences grouped by internal tax code and "
                    + "sample lines with both quotes (tax:read). Use it to answer whether a provider is ready to take "
                    + "over; the console's Shadow report page gives the cut-over checks.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult taxShadowReport(McpTransportContext context,
                               @McpToolParam(description = "7, 30 (default) or 90", required = false) Integer days,
                               @McpToolParam(description = "STRIPE or AVALARA (default: the provider in shadow mode)",
                                       required = false) String provider) {
        return tax.shadowReport(McpCallerExtractor.caller(context), days, provider);
    }
}
