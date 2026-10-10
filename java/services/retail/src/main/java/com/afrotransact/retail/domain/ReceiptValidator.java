package com.afrotransact.retail.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dock validation of a goods receipt before it can be completed (S072). */
public interface ReceiptValidator {

    /**
     * @param receipt      the receipt with counted lines
     * @param items        product master of the receipt's items, by id
     * @param existingLots lots already received, as {@code SKU|LOT}
     * @param today        receiving date at the node
     */
    ReceiptValidation validate(GoodsReceipt receipt, Map<UUID, RetailItem> items, Set<String> existingLots,
                               LocalDate today);

    /** Shortages (claim against the supplier), damaged units (claim against the carrier) and accepted overs. */
    List<Discrepancy> discrepancies(GoodsReceipt receipt, ReceiptValidation validation);

    static String lotKey(String sku, String lotNo) {
        return sku + "|" + lotNo;
    }
}
