package com.afrotransact.retail.port;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;

/** Renders documents sent to suppliers. */
public interface PurchaseOrderDocuments {

    /** ANSI X12 850 purchase order (EDI) for the supplier, addressed to the supplier code. */
    default String edi850(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items) {
        return edi850(po, supplier, items, supplier.code());
    }

    /**
     * ANSI X12 850 addressed to {@code receiverId} (the trading partner's ISA receiver id from its EDI profile).
     */
    String edi850(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items, String receiverId);
}
