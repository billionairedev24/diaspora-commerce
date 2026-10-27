package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.GiftCard;

/** Gift cards. The code is only ever looked up by its keyed hash. */
public interface GiftCardRepository {

    void insert(GiftCard card);

    Optional<GiftCard> find(UUID id);

    /** Locks the row until the transaction ends. */
    Optional<GiftCard> lock(UUID id);

    Optional<GiftCard> lockByHash(String codeHash);

    Optional<GiftCard> lockByIntent(String paymentIntentId);

    /** Optimistic: fails when the card changed since it was read. */
    GiftCard update(GiftCard card);

    List<GiftCard> ofPurchaser(String purchaserId, int limit);

    /**
     * Staff lookup: by card id, last four of the code, or part of the recipient email / sender name; newest first.
     * {@code status} null for every status.
     */
    List<GiftCard> search(String query, GiftCard.Status status, int limit);

    /** Active cards scheduled to be sent at or before {@code now}, locked and skipping rows another worker holds. */
    List<GiftCard> lockDue(Instant now, int limit);

    /** Per status: number of cards and Σ amount. */
    Map<GiftCard.Status, long[]> totalsByStatus();
}
