package com.afrotransact.retail.port;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;

/** Renders the purchase order PDF sent to the supplier: header, ship-to, lines, terms and totals (S225). */
public interface PurchaseOrderPdfRenderer {

    byte[] render(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items);
}
