package com.afrotransact.mcp.port;

/** cart-service {@code GET /v1/cart}. */
public interface CartApi {
    Downstream cart(String bearerToken);
}
