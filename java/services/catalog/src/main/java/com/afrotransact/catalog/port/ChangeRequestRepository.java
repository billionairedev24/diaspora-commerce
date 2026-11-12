package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ProductChangeRequest;

/** Store of {@link ProductChangeRequest}s (optimistic locking on {@code version}). */
public interface ChangeRequestRepository {

    ProductChangeRequest insert(ProductChangeRequest request);

    /**
     * Stores a decision or withdrawal when {@code request.version()} is still the stored one and returns it with
     * version + 1; a request that is no longer PENDING in the database is refused the same way.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when somebody decided or changed it meanwhile
     */
    ProductChangeRequest update(ProductChangeRequest request);

    Optional<ProductChangeRequest> findById(UUID id);

    /** Whether this organisation already has a waiting request of this type and field on the page. */
    boolean existsOpen(UUID productId, ChangeRequestType type, String field, String requestedByOrg);

    /** Whether a waiting WRONG_MATCH report names this barcode (the 14-digit key). */
    boolean existsOpenWrongMatch(String gtin14);

    /** The requests on a page, newest first; with {@code requestedByOrg} only that organisation's. */
    List<ProductChangeRequest> findByProduct(UUID productId, String requestedByOrg);

    Page search(Query query);

    /** How many requests are waiting. */
    long countOpen();

    /** When the oldest waiting request was filed. */
    Optional<Instant> oldestOpenSince();

    /**
     * @param requestedByOrg null for every organisation (staff)
     * @param contentOnly    leave out the wrong-match and duplicate-page reports (the "Change requests" table of the staff
     *                       console; the reports have tables of their own)
     * @param oldestFirst    the order of the SLA clock
     */
    record Query(ChangeRequestStatus status, ChangeRequestType type, boolean contentOnly, String requestedByOrg,
                 boolean oldestFirst, int page, int size) {

        /** Every type. */
        public Query(ChangeRequestStatus status, ChangeRequestType type, String requestedByOrg, boolean oldestFirst, int page,
                     int size) {
            this(status, type, false, requestedByOrg, oldestFirst, page, size);
        }
    }

    record Page(List<ProductChangeRequest> items, long total, int page, int size) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
