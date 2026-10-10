package com.afrotransact.retail.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;

/** Persistence port for purchase orders; {@code update} fails with a conflict when the version moved. */
public interface PurchaseOrderRepository {

    PurchaseOrder insert(PurchaseOrder po);

    PurchaseOrder update(PurchaseOrder po);

    Optional<PurchaseOrder> findById(UUID id);

    Optional<PurchaseOrder> findByNumber(String number);

    Page<PurchaseOrder> page(PoStatus status, UUID supplierId, int page, int size);
}
