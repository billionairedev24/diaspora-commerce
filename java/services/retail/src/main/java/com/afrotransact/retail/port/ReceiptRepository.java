package com.afrotransact.retail.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.ReceiptStatus;

/** Persistence port for goods receipts. */
public interface ReceiptRepository {

    GoodsReceipt insert(GoodsReceipt receipt);

    GoodsReceipt update(GoodsReceipt receipt);

    Optional<GoodsReceipt> findById(UUID id);

    Optional<GoodsReceipt> findByNumber(String number);

    /** Receipts of a shipment that are not completed (at most one is allowed). */
    Optional<GoodsReceipt> findActiveByShipment(UUID shipmentId);

    List<GoodsReceipt> findByPo(UUID poId);

    Page<GoodsReceipt> page(ReceiptStatus status, UUID poId, int page, int size);
}
