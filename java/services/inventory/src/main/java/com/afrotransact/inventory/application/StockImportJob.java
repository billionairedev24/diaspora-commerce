package com.afrotransact.inventory.application;

import java.util.UUID;

/**
 * Applies a stock file import row by row (S395). Each row is its own transaction: it locks the position, then the lot,
 * checks the owner and the reserved units, writes the ledger movement and records the row's outcome together, so a crash or
 * a second pod can never apply a row twice, and a count never takes a lot below its reserved units.
 */
public interface StockImportJob {

    /** Runs the import until it is complete. */
    void drive(UUID importId);
}
