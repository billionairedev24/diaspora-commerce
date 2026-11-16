package com.afrotransact.ai.port;

import com.afrotransact.ai.port.AccountReader.CartSummary;
import com.afrotransact.ai.port.AccountReader.Result;

/** The shopper's cart (cart service {@code GET /v1/cart}); the cart is a Go service, so this edge stays HTTP. */
public interface CartReader {

    Result<CartSummary> cart(Shopper shopper);
}
