package com.afrotransact.payments.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;

/** AfroTransact Retail supplier payments (S226), one per purchase order. */
public interface SupplierPaymentRepository {

    /** false when the purchase order already has a payment (business-key de-duplication). */
    boolean insert(SupplierPayment payment);

    Optional<SupplierPayment> find(UUID id);

    /** SELECT … FOR UPDATE. */
    Optional<SupplierPayment> lock(UUID id);

    /** Optimistic update (status, journals, reference, hold reason); returns the stored row. */
    SupplierPayment update(SupplierPayment payment);

    /**
     * Payments newest due date first.
     *
     * @param dueBy only payments due on or before this day (null: any)
     */
    List<SupplierPayment> list(Optional<SupplierPaymentStatus> status, Optional<UUID> supplierId, LocalDate dueBy,
                               int limit);
}
