package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.PaymentMethod;

/** Card and bank payment methods of an organisation (no PAN or account numbers, only brand / bank and last 4). */
public interface PaymentMethodRepository {

    void insert(PaymentMethod method);

    List<PaymentMethod> list(String orgId);

    Optional<PaymentMethod> find(String orgId, UUID id);

    /** Makes {@code id} the only default of the organisation. */
    void makeDefault(String orgId, UUID id);

    void delete(String orgId, UUID id);

    /**
     * A plan SetupIntent succeeded (Stripe, through payments): the saved bank account is verified (instantly with
     * Financial Connections, or after microdeposits) and its online ACH mandate is kept. Unknown methods are ignored.
     */
    void markVerified(String stripePaymentMethodId, String mandateId);

    /** The ACH mandate stored with the payment method, if any. */
    Optional<String> mandate(String orgId, String stripePaymentMethodId);
}
