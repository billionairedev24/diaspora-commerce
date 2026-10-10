package com.afrotransact.inventory.application;

import com.afrotransact.inventory.domain.StockPosition;

/** Goods receipts and inbound notices. */
public interface ReceivingService {

    /**
     * Creates one lot per batch line: sellable, or quarantined when short-dated; damaged units become a separate
     * {@code <lot>-DMG} quarantine lot. Expired lots and impossible dates reject the whole receipt (422).
     * Idempotent per {@code Idempotency-Key}.
     */
    ReceiptResult receive(ReceiveCommand command, String idempotencyKey);

    StockPosition expectInbound(InboundCommand command);
}
