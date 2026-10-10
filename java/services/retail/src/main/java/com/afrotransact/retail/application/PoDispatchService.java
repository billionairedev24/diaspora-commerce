package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.domain.PoDispatch;
import com.afrotransact.retail.domain.SupplierEdiProfile;

/**
 * Sending purchase orders to suppliers (S225). Sending a PO ({@code POST /v1/purchase-orders/{id}/send}) queues an
 * EMAIL dispatch (PDF stored in media, signed link emailed to the supplier contact through notifications) and, for
 * EDI-enabled suppliers, an EDI dispatch (X12 850 through the EDI transport). A job works them off with retries.
 */
public interface PoDispatchService {

    /** The PO as PDF (lines, terms, totals); approved POs and later only ({@code retail:read}). */
    byte[] pdf(UUID poId);

    /** Deliveries of a PO ({@code retail:read}). */
    List<PoDispatch> dispatches(UUID poId);

    /** Puts the PO's FAILED dispatches back in the queue ({@code retail:po:write}). */
    List<PoDispatch> retryFailed(UUID poId);

    /** Works off due dispatches (job). @return how many were attempted */
    int dispatchDue();

    /** A supplier's EDI settings ({@code retail:read}); empty profile when none. */
    SupplierEdiProfile ediProfile(UUID supplierId);

    /** Sets a supplier's EDI settings ({@code retail:suppliers:write}). */
    SupplierEdiProfile setEdiProfile(UUID supplierId, EdiProfileCommand command);

    /** New EDI settings. */
    record EdiProfileCommand(boolean enabled, EdiProtocol protocol, String interchangeId, String endpoint) {
    }
}
