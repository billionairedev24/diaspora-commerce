package com.afrotransact.orders.port;

/** Encrypts handover codes so only the customer's own order view can show them (the hash is used for checks). */
public interface HandoverCodeVault {

    String seal(String shipmentId, String code);

    String reveal(String shipmentId, String sealed);
}
