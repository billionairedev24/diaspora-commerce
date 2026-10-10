package com.afrotransact.retail.port;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;

/** Renders documents sent to suppliers. */
public interface PurchaseOrderDocuments {

    /** ANSI X12 850 purchase order (EDI) for the supplier. */
    String edi850(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items);
}
