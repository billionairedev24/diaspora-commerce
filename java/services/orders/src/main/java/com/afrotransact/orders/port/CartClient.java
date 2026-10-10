package com.afrotransact.orders.port;

import java.util.List;
import java.util.Map;

/** cart-service: the shopper's cart (with their token) and clearing ordered lines (cart:service). */
public interface CartClient {

    /** {@code GET /v1/cart} relaying the customer's bearer token. */
    Snapshot cart(String customerBearer);

    /** {@code POST /v1/carts/{customerId}/ordered}: removes the checked-out lines. Idempotent. */
    void clearOrdered(String customerId, List<String> variantAtins);

    /**
     * Cart view at checkout time.
     *
     * @param preorderFulfilment door / pickup chosen per pre-order window ({@code PUT /v1/cart/preorder-fulfilment},
     *                           S272): window id → DOOR | PICKUP
     */
    record Snapshot(String currency, boolean checkoutable, long version, List<Item> items,
                    Map<String, String> preorderFulfilment) {

        public Snapshot {
            items = items == null ? List.of() : List.copyOf(items);
            preorderFulfilment = preorderFulfilment == null ? Map.of() : Map.copyOf(preorderFulfilment);
        }

        public Snapshot(String currency, boolean checkoutable, long version, List<Item> items) {
            this(currency, checkoutable, version, items, Map.of());
        }
    }

    /**
     * One cart line.
     *
     * @param fulfilment AFROTRANSACT (FBA or 1P stock in our FCs) or SELLER
     * @param sellerKind PLATFORM (AfroTransact Retail) or SELLER
     * @param offerId    offers-service offer of the line (null for catalog-only lines); coupons price by offer
     */
    record Item(String variantAtin, String productAtin, String title, String imageUrl, String sellerOrgId,
                String sellerName, String sellerKind, String fulfilment, long priceMinor, String currency,
                int quantity, boolean unavailable, String offerId) {
    }
}
