package com.afrotransact.identity.application;

import java.util.List;

/**
 * The signed-in user's guided-tour progress (Seller Center, Provider Hub and staff console). Every call is about the
 * caller only; nobody can read or write another person's progress. Writes are idempotent.
 */
public interface TourProgressService {

    TourProgressSnapshot snapshot();

    /**
     * Idempotent upsert of one tour. A newer content version replaces an older one, an older version is ignored, and
     * for the same version "in progress" never undoes completed or dismissed. Returns what is stored afterwards.
     * 422 identity.invalid-tour-progress for a bad id, version or status; identity.tour-progress-limit past 200 tours.
     */
    TourProgressView save(SaveTourProgressCommand command);

    /**
     * Merges many records (the first sync copies what the browser had) in one transaction: a tour the server does not
     * have yet is added, a newer content version replaces an older one, and anything the server already holds for that
     * version stands (server state is the truth). Returns the progress afterwards.
     */
    TourProgressSnapshot merge(List<SaveTourProgressCommand> commands);

    /** Forgets one tour ("Replay with auto-start again"). Idempotent. */
    void reset(String tourId);

    /** Forgets every tour of the caller ("Reset my progress"). Idempotent. */
    void resetAll();
}
