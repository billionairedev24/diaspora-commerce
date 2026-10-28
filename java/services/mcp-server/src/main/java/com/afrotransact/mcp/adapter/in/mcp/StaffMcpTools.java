package com.afrotransact.mcp.adapter.in.mcp;

import com.afrotransact.mcp.application.StaffToolsService;
import com.afrotransact.mcp.application.ToolCatalog;
import com.afrotransact.mcp.application.ToolResult;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Staff toolset (scope {@code mcp:staff}, staff accounts only). */
@Component
class StaffMcpTools {

    private final StaffToolsService staff;

    StaffMcpTools(StaffToolsService staff) {
        this.staff = staff;
    }

    @McpTool(name = ToolCatalog.APPLICATIONS_QUEUE, title = "Applications queue",
            description = "Seller and provider applications waiting for review (applications:review).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult applicationsQueue(McpTransportContext context,
                                 @McpToolParam(description = "Status filter (default IN_REVIEW)", required = false)
                                 String status,
                                 @McpToolParam(description = "Page number from 0", required = false) Integer page) {
        return staff.applicationsQueue(McpCallerExtractor.caller(context), status, page);
    }

    @McpTool(name = ToolCatalog.APPLICATION_DETAIL, title = "Application detail",
            description = "One application with checks and documents. Personal data is masked: tax ids and account "
                    + "numbers to the last four, dates of birth to the year, emails and phones partially.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult applicationDetail(McpTransportContext context,
                                 @McpToolParam(description = "Application id") String applicationId) {
        return staff.applicationDetail(McpCallerExtractor.caller(context), applicationId);
    }

    @McpTool(name = ToolCatalog.MODERATION_QUEUE, title = "Moderation queue",
            description = "Listings waiting for moderation (catalog:moderate).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult moderationQueue(McpTransportContext context,
                               @McpToolParam(description = "Page number from 0", required = false) Integer page) {
        return staff.moderationQueue(McpCallerExtractor.caller(context), page);
    }

    @McpTool(name = ToolCatalog.ORDER_LOOKUP, title = "Order lookup",
            description = "One order with its shipments and status (orders:read). Personal data is masked.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult orderLookup(McpTransportContext context,
                           @McpToolParam(description = "Order number or id") String orderId) {
        return staff.orderLookup(McpCallerExtractor.caller(context), orderId);
    }

    @McpTool(name = ToolCatalog.SELLER_PAYOUT_STATUS, title = "Seller payout status",
            description = "A seller's or provider's payout balance: available now, held orders with the date each is "
                    + "released, in transit, paid, and whether payouts are paused and why (ledger:read). Use it to "
                    + "answer 'why has this seller not been paid'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult sellerPayoutStatus(McpTransportContext context,
                                  @McpToolParam(description = "Organisation id") String orgId) {
        return staff.sellerPayoutStatus(McpCallerExtractor.caller(context), orgId);
    }

    @McpTool(name = ToolCatalog.SELLER_ACCOUNT_HEALTH, title = "Seller account health",
            description = "What Stripe and AfroTransact still need from a seller or provider, deadlines, and whether "
                    + "charges, payouts or listings are switched off (applications:review).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult sellerAccountHealth(McpTransportContext context,
                                   @McpToolParam(description = "Organisation id") String orgId) {
        return staff.sellerAccountHealth(McpCallerExtractor.caller(context), orgId);
    }

    @McpTool(name = ToolCatalog.ORDER_PAYMENT_SPLIT, title = "Order payment split",
            description = "How an order's payment splits between sellers, AfroTransact fees, Stripe fees and tax "
                    + "(ledger:read).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ToolResult orderPaymentSplit(McpTransportContext context,
                                 @McpToolParam(description = "Order id") String orderId) {
        return staff.orderPaymentSplit(McpCallerExtractor.caller(context), orderId);
    }
}
