package com.afrotransact.inventory.application;

/** Customer returns after inspection: RETURN_RESTOCK or RETURN_DAMAGED (inventory:adjust). */
public interface ReturnService {

    /** Never accepts more units than the order shipped. Idempotent per {@code Idempotency-Key}. */
    ReturnResult accept(ReturnCommand command, String idempotencyKey);
}
