package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.CreditAdjustment;

/** Staff credit grants and corrections, proposed, posted or rejected. */
public interface CreditAdjustmentRepository {

    void insert(CreditAdjustment adjustment);

    Optional<CreditAdjustment> find(UUID id);

    Optional<CreditAdjustment> lock(UUID id);

    CreditAdjustment update(CreditAdjustment adjustment);

    /** Newest first; {@code status} null for every status, {@code customerId} null for every customer. */
    List<CreditAdjustment> list(AdjustmentStatus status, String customerId, int limit);
}
