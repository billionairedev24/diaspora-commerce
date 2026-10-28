package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipCharge;

/** Every attempt to charge the membership fee. */
public interface MembershipChargeRepository {

    void insert(MembershipCharge charge);

    MembershipCharge update(MembershipCharge charge);

    Optional<MembershipCharge> find(UUID id);

    Optional<MembershipCharge> lock(UUID id);

    Optional<MembershipCharge> findByKey(String idempotencyKey);

    /** Newest first. */
    List<MembershipCharge> ofMembership(UUID membershipId, int limit);

    /** Newest first. */
    List<MembershipCharge> ofCustomer(String customerId, int limit);

    /** The newest charge that succeeded and was not refunded. */
    Optional<MembershipCharge> latestPaid(UUID membershipId);
}
