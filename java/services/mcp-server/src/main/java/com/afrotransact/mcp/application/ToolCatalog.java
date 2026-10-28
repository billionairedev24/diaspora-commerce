package com.afrotransact.mcp.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.domain.Toolset;

/** Access rules of every MCP tool (names must match the {@code @McpTool} names of the inbound adapter). */
public final class ToolCatalog {

    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String GET_PRODUCT = "get_product";
    public static final String CHECK_DELIVERY = "check_delivery";
    public static final String GET_CART = "get_cart";
    public static final String PROPOSE_ADD_TO_CART = "propose_add_to_cart";
    public static final String TRACK_ORDER = "track_order";
    public static final String START_RETURN_PROPOSAL = "start_return_proposal";
    public static final String FIND_SERVICE = "find_service";
    public static final String MY_ORDER_HISTORY = "my_order_history";

    public static final String LIST_MY_PRODUCTS = "list_my_products";
    public static final String PRODUCT_STATUS = "product_status";
    public static final String LIST_MY_ORDERS = "list_my_orders";
    public static final String UPDATE_SHIPMENT_STATUS = "update_shipment_status";
    public static final String CHECK_INVENTORY = "check_inventory";
    public static final String REQUEST_CATEGORY = "request_category";
    public static final String GET_MY_ORDER = "get_my_order";
    public static final String PAYOUT_BALANCE = "payout_balance";
    public static final String LIST_PAYOUTS = "list_payouts";
    public static final String ACCOUNT_HEALTH = "account_health";

    public static final String APPLICATIONS_QUEUE = "applications_queue";
    public static final String APPLICATION_DETAIL = "application_detail";
    public static final String MODERATION_QUEUE = "moderation_queue";
    public static final String ORDER_PAYMENT_SPLIT = "order_payment_split";
    public static final String ORDER_LOOKUP = "order_lookup";
    public static final String SELLER_PAYOUT_STATUS = "seller_payout_status";
    public static final String SELLER_ACCOUNT_HEALTH = "seller_account_health";

    private static final Map<String, ToolDescriptor> TOOLS = index(List.of(
            new ToolDescriptor(SEARCH_PRODUCTS, Toolset.SHOPPER, null, true),
            new ToolDescriptor(GET_PRODUCT, Toolset.SHOPPER, null, true),
            new ToolDescriptor(CHECK_DELIVERY, Toolset.SHOPPER, null, true),
            new ToolDescriptor(GET_CART, Toolset.SHOPPER, null, true),
            new ToolDescriptor(PROPOSE_ADD_TO_CART, Toolset.SHOPPER, null, true),
            new ToolDescriptor(TRACK_ORDER, Toolset.SHOPPER, null, true),
            new ToolDescriptor(START_RETURN_PROPOSAL, Toolset.SHOPPER, null, true),
            new ToolDescriptor(FIND_SERVICE, Toolset.SHOPPER, null, true),
            new ToolDescriptor(MY_ORDER_HISTORY, Toolset.SHOPPER, null, true),
            new ToolDescriptor(LIST_MY_PRODUCTS, Toolset.SELLER, McpPermissions.CATALOG_WRITE, true),
            new ToolDescriptor(PRODUCT_STATUS, Toolset.SELLER, McpPermissions.CATALOG_WRITE, true),
            new ToolDescriptor(LIST_MY_ORDERS, Toolset.SELLER, McpPermissions.ORDERS_READ, true),
            new ToolDescriptor(UPDATE_SHIPMENT_STATUS, Toolset.SELLER, McpPermissions.ORDERS_UPDATE_STATUS, false),
            new ToolDescriptor(CHECK_INVENTORY, Toolset.SELLER, McpPermissions.INVENTORY_READ, true),
            new ToolDescriptor(REQUEST_CATEGORY, Toolset.SELLER, McpPermissions.CATALOG_WRITE, false),
            new ToolDescriptor(GET_MY_ORDER, Toolset.SELLER, McpPermissions.ORDERS_READ, true),
            // Money and account tools carry no tool permission: payments and seller-service decide per member role
            // (owner, finance ...) and answer FORBIDDEN, which the tool passes on.
            new ToolDescriptor(PAYOUT_BALANCE, Toolset.SELLER, null, true),
            new ToolDescriptor(LIST_PAYOUTS, Toolset.SELLER, null, true),
            new ToolDescriptor(ACCOUNT_HEALTH, Toolset.SELLER, null, true),
            new ToolDescriptor(APPLICATIONS_QUEUE, Toolset.STAFF, McpPermissions.APPLICATIONS_REVIEW, true),
            new ToolDescriptor(APPLICATION_DETAIL, Toolset.STAFF, McpPermissions.APPLICATIONS_REVIEW, true),
            new ToolDescriptor(MODERATION_QUEUE, Toolset.STAFF, McpPermissions.CATALOG_MODERATE, true),
            new ToolDescriptor(ORDER_PAYMENT_SPLIT, Toolset.STAFF, McpPermissions.LEDGER_READ, true),
            new ToolDescriptor(ORDER_LOOKUP, Toolset.STAFF, McpPermissions.ORDERS_READ, true),
            new ToolDescriptor(SELLER_PAYOUT_STATUS, Toolset.STAFF, McpPermissions.LEDGER_READ, true),
            new ToolDescriptor(SELLER_ACCOUNT_HEALTH, Toolset.STAFF, McpPermissions.APPLICATIONS_REVIEW, true)));

    private ToolCatalog() {
    }

    public static List<ToolDescriptor> all() {
        return List.copyOf(TOOLS.values());
    }

    public static Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(TOOLS.get(name));
    }

    private static Map<String, ToolDescriptor> index(List<ToolDescriptor> tools) {
        Map<String, ToolDescriptor> m = new LinkedHashMap<>();
        tools.forEach(t -> m.put(t.name(), t));
        return Collections.unmodifiableMap(m);
    }
}
