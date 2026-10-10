package com.afrotransact.orders.port;

import java.util.List;
import java.util.Map;

/** promise-service {@code POST /v1/promises}. May be unavailable: callers fall back to stored estimates. */
public interface PromiseClient {

    /** Business days to delivery per ATIN; empty when the service does not answer. */
    Map<String, Integer> days(String zip, List<Item> items);

    /** One item to promise. */
    record Item(String atin, String fulfilment, String sellerOrgId) {
    }
}
