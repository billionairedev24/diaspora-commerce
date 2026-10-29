package com.afrotransact.seller.application;

/**
 * Keeps payments-service's list of Connect accounts equal to ours. Payments transfers a seller's net only to an account
 * it knows; the first registration happens when the account is created and can fail, so this repeats it for every
 * connected account (an upsert at payments, so repeating is harmless).
 */
public interface ConnectedAccountSyncService {

    /** Registers every connected account with payments; returns how many were accepted. */
    int registerAll();

    /** The same run started by staff holding {@code compliance:remind} (Ops, Finance); returns how many were accepted. */
    int syncNow();
}
