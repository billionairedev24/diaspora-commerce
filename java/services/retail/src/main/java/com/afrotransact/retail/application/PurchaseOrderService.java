package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.port.Page;

/**
 * Purchase orders with approval thresholds and FX (S070). Every mutation takes the version the caller last saw
 * ({@code If-Match}); a stale version fails with 409.
 */
public interface PurchaseOrderService {

    PurchaseOrder create(CreatePoCommand command);

    PurchaseOrder replaceLines(UUID id, List<PoLineInput> lines, Long expectedVersion);

    PurchaseOrder submit(UUID id, Long expectedVersion);

    /** {@code retail:po:approve}; above the Finance threshold also {@code retail:finance}. */
    PurchaseOrder approve(UUID id, String note, Long expectedVersion);

    PurchaseOrder sendBack(UUID id, String note, Long expectedVersion);

    PurchaseOrder send(UUID id, Long expectedVersion);

    PurchaseOrder confirm(UUID id, String note, Long expectedVersion);

    PurchaseOrder cancel(UUID id, String reason, Long expectedVersion);

    /** Finance closes a received, fully matched PO; this releases supplier payment. */
    PurchaseOrder close(UUID id, Long expectedVersion);

    PurchaseOrder get(UUID id);

    Page<PurchaseOrder> list(PoStatus status, UUID supplierId, int page, int size);

    /** The X12 850 document sent to the supplier. */
    String edi850(UUID id);
}
