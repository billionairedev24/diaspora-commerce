package com.afrotransact.retail.port;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Lot numbers already received per SKU (lots are unique per SKU across all receipts). */
public interface LotRegistry {

    /** Of the given {@code SKU|LOT} keys, those already registered by other receipts. */
    Set<String> existing(Collection<String> keys, UUID exceptReceiptId);

    /** Registers the receipt's lots; fails with a conflict when another receipt registered one meanwhile. */
    void register(UUID receiptId, Collection<String> keys);

    /** Releases the receipt's lots (inventory rejected the receipt). */
    void release(UUID receiptId);
}
