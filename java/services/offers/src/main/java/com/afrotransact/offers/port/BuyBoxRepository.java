package com.afrotransact.offers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.Ineligibility;
import com.afrotransact.offers.domain.ScoreComponent;

/** Current buy box winner per (ATIN, currency) and the audit trail of every winner change. */
public interface BuyBoxRepository {

    /** Serialises recomputations of one ATIN for the rest of the transaction. */
    void lock(String atin);

    Optional<CurrentBuyBox> find(String atin, String currency);

    List<String> currencies(String atin);

    void save(CurrentBuyBox current);

    void audit(AuditEntry entry);

    List<AuditEntry> history(String atin, int limit);

    /** The stored winner; {@code winnerOfferId} is null when nothing is eligible. */
    record CurrentBuyBox(String atin, String currency, UUID winnerOfferId, String winnerSellerOrgId,
                         Long winnerPriceMinor, Long winnerLandedPriceMinor, int offerCount, Long minLandedPriceMinor,
                         double score, List<BuyBoxReason> reasons, Instant computedAt) {

        public CurrentBuyBox {
            reasons = List.copyOf(reasons);
        }
    }

    /** A recorded winner change with the full evaluation that produced it. */
    record AuditEntry(UUID id, String atin, String currency, UUID previousWinnerOfferId, UUID winnerOfferId,
                      String trigger, List<AuditedOffer> offers, List<BuyBoxReason> reasons, Instant decidedAt) {

        public AuditEntry {
            offers = List.copyOf(offers);
            reasons = List.copyOf(reasons);
        }
    }

    /** One offer as it was evaluated in an audited decision. */
    record AuditedOffer(UUID offerId, String sellerOrgId, int rank, double score, boolean buyBoxEligible,
                        boolean listable, List<Ineligibility> ineligibility, long landedPriceMinor,
                        int availableQty, List<ScoreComponent> components) {
    }
}
