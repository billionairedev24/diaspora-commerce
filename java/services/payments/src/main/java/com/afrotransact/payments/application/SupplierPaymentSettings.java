package com.afrotransact.payments.application;

/**
 * AfroTransact Retail supplier payment settings (S226).
 *
 * @param defaultTermsDays days from release to the payment date when retail-service sends no due date (net terms)
 */
public record SupplierPaymentSettings(int defaultTermsDays) {

    public SupplierPaymentSettings {
        if (defaultTermsDays < 0 || defaultTermsDays > 180) {
            throw new IllegalArgumentException("Supplier payment terms must be 0–180 days");
        }
    }
}
