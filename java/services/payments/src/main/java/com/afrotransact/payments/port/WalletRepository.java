package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.domain.SavedPaymentMethod;

/** Stripe Customers of AfroTransact customers and their saved payment methods. */
public interface WalletRepository {

    /** Mapping AfroTransact customer id ↔ Stripe customer id, with the default method. */
    record StripeCustomer(String customerId, String stripeCustomerId, String defaultPaymentMethodId) {
    }

    /** false when the customer already has a Stripe Customer. */
    boolean insertCustomer(String customerId, String stripeCustomerId, Instant now);

    Optional<StripeCustomer> findCustomer(String customerId);

    Optional<StripeCustomer> lockCustomer(String customerId);

    Optional<StripeCustomer> findByStripeCustomer(String stripeCustomerId);

    void setDefault(String customerId, String paymentMethodId);

    void upsertMethod(SavedPaymentMethod method);

    /** Methods not detached, oldest first. */
    List<SavedPaymentMethod> methods(String customerId);

    Optional<SavedPaymentMethod> findMethod(String paymentMethodId);

    void markDetached(String paymentMethodId, Instant now);
}
