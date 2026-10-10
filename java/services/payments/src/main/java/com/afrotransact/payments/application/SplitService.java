package com.afrotransact.payments.application;

/** Staff view of how an order's charge settles to each recipient (permission payments:read). */
public interface SplitService {

    OrderSplit splitOf(String orderId);
}
