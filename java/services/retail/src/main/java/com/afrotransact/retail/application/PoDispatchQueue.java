package com.afrotransact.retail.application;

import java.time.Instant;

import com.afrotransact.retail.domain.DispatchChannel;
import com.afrotransact.retail.domain.PoDispatch;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.port.PoDispatchRepository;
import com.afrotransact.retail.port.SupplierEdiProfileRepository;
import org.springframework.stereotype.Component;

/** Queues a sent PO's deliveries in the same transaction as the send (one per channel, never twice). */
@Component
class PoDispatchQueue {

    private final PoDispatchRepository dispatches;
    private final SupplierEdiProfileRepository ediProfiles;

    PoDispatchQueue(PoDispatchRepository dispatches, SupplierEdiProfileRepository ediProfiles) {
        this.dispatches = dispatches;
        this.ediProfiles = ediProfiles;
    }

    void enqueue(PurchaseOrder po, Supplier supplier, Instant now) {
        String email = supplier.purchaseOrderContact().map(SupplierContact::email).orElse(null);
        dispatches.insertIfAbsent(PoDispatch.pending(po.id(), po.number(), DispatchChannel.EMAIL, email, now));
        ediProfiles.find(supplier.id()).filter(p -> p.enabled()).ifPresent(p -> dispatches.insertIfAbsent(
                PoDispatch.pending(po.id(), po.number(), DispatchChannel.EDI, p.interchangeId(), now)));
    }
}
