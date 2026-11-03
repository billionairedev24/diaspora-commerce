package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateAudit;
import com.afrotransact.payments.domain.NegotiatedRateType;

/** Negotiated commission and connection-fee rates with their audit trail. Rows are never deleted. */
public interface NegotiatedRateRepository {

    void insert(NegotiatedRate rate);

    /** Saves an ended or expired rate; fails on a concurrent change (optimistic locking on {@code version}). */
    NegotiatedRate update(NegotiatedRate rate);

    Optional<NegotiatedRate> findById(UUID id);

    /** Locks the organisation's rates of one type (SELECT … FOR UPDATE) so overlap checks are race-free. */
    List<NegotiatedRate> lockForOrg(String orgId, NegotiatedRateType type);

    /** Every rate of these organisations that may apply at some time (not ended before it started). */
    List<NegotiatedRate> forOrgs(Collection<String> orgIds);

    /**
     * Rates for the console: optionally one organisation and one type, newest first, at most {@code limit}.
     */
    List<NegotiatedRate> search(String orgId, NegotiatedRateType type, int limit);

    /** Rates past their end date whose expiry is not recorded yet, oldest first. */
    List<NegotiatedRate> dueForExpiry(Instant now, int limit);

    void insertAudit(NegotiatedRateAudit entry);

    List<NegotiatedRateAudit> audit(UUID rateId);
}
