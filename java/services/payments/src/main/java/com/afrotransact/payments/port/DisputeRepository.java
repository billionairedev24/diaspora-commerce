package com.afrotransact.payments.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.domain.Dispute;

/** Chargebacks. */
public interface DisputeRepository {

    /** false when the dispute is already recorded. */
    boolean insert(Dispute dispute);

    Optional<Dispute> lock(String id);

    void update(Dispute dispute);

    List<Dispute> ofPayment(UUID paymentId);

    /** Those of {@code orderIds} with an OPEN dispute (plan fees are never withheld from them, S298). */
    Set<String> openOrders(Collection<String> orderIds);
}
