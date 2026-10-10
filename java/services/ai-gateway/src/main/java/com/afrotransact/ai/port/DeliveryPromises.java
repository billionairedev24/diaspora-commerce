package com.afrotransact.ai.port;

import java.util.Optional;

/** promise-service {@code GET /v1/promises?atin=&zip=}: delivery promise to a ZIP ("Tomorrow, Oct 10"). */
public interface DeliveryPromises {

    /** Empty when the promise service is not available; the tool then says so instead of guessing. */
    Optional<Promise> promise(String atin, String zip, String bearerToken);

    /**
     * A delivery promise.
     *
     * @param deliverable false when the item cannot be delivered to the ZIP
     * @param label       human promise, for example "Tomorrow, Oct 10"
     * @param earliest    ISO date
     * @param latest      ISO date
     * @param method      delivery method
     */
    record Promise(boolean deliverable, String label, String earliest, String latest, String method) {
    }
}
