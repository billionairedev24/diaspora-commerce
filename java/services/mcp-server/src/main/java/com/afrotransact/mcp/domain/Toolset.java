package com.afrotransact.mcp.domain;

/** A group of MCP tools with the OAuth scope / permission a client needs to see and call them. */
public enum Toolset {
    /** Shopping on the shopper's behalf: search, product, delivery, cart (read), orders, returns, services. */
    SHOPPER("mcp:shopper"),
    /** Seller back office for the caller's organisation. */
    SELLER("mcp:seller"),
    /** Staff queues and payment views (staff principals only). */
    STAFF("mcp:staff");

    private final String permission;

    Toolset(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }
}
