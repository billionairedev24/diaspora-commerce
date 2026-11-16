package com.afrotransact.ai.port;

import java.util.List;

import com.afrotransact.ai.port.AccountReader.OrderSummary;
import com.afrotransact.ai.port.AccountReader.Result;

/** The shopper's recent orders (orders {@code GET /v1/orders?customer=me}); one edge, ai-gateway -> orders. */
public interface OrderHistory {

    Result<List<OrderSummary>> recent(Shopper shopper, int limit);
}
