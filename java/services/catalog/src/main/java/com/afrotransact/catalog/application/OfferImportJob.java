package com.afrotransact.catalog.application;

import java.util.UUID;

/**
 * Drives an offer upload or a price and quantity feed (S393, S394) row by row through offers-service. Resumable: every batch
 * is saved with its rows' outcomes, offers-service applies a repeated row as "no change", so a crash or a second pod never
 * creates an offer twice. Never creates a product and never sets a status.
 */
public interface OfferImportJob {

    /** Runs the upload until it is complete, or until offers-service stops answering (the resume job continues it). */
    void drive(UUID importId);
}
