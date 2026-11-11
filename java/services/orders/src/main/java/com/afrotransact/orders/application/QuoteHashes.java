package com.afrotransact.orders.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.QuoteSnapshot;
import com.afrotransact.orders.domain.QuoteSnapshot.LineKey;
import com.afrotransact.orders.port.CartClient;

/** The cart hash of a quote, computed the same way when it is asked for and when it is used at checkout. */
final class QuoteHashes {

    private QuoteHashes() {
    }

    static String cart(List<CartClient.Item> items) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, LineKey> keys = new LinkedHashMap<>();
        for (CartClient.Item i : items) {
            String seller = i.sellerOrgId() == null ? "" : i.sellerOrgId();
            String k = i.variantAtin() + "|" + seller;
            quantities.merge(k, i.quantity(), Integer::sum);
            keys.putIfAbsent(k, new LineKey(i.variantAtin(), seller, 0));
        }
        return QuoteSnapshot.hashLines(keys.entrySet().stream()
                .map(e -> new LineKey(e.getValue().variantAtin(), e.getValue().sellerOrgId(),
                        quantities.get(e.getKey()))).toList());
    }
}
