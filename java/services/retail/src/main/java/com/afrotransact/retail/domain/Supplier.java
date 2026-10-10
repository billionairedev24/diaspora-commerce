package com.afrotransact.retail.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A vendor AfroTransact Retail buys from: trading currency, payment terms, lead time, certifications, contacts,
 * lifecycle status and a receipt-driven scorecard.
 */
public record Supplier(UUID id, String code, String name, String country, String city, String currency,
                       String paymentTerms, int leadTimeDays, List<String> certifications,
                       List<SupplierContact> contacts, SupplierStatus status, String statusReason,
                       SupplierScorecard scorecard, Instant createdAt, Instant updatedAt, long version) {

    public Supplier {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        if (code == null || !code.matches("SUP-[A-Z0-9]{2,12}")) {
            throw new RetailRuleViolation("retail.supplier-invalid", "Supplier code must look like SUP-ACC");
        }
        if (name == null || name.isBlank()) {
            throw new RetailRuleViolation("retail.supplier-invalid", "Supplier name is required");
        }
        if (country == null || !country.matches("[A-Z]{2}")) {
            throw new RetailRuleViolation("retail.supplier-invalid", "Country must be an ISO 3166 alpha-2 code");
        }
        currency = Currencies.require(currency);
        if (leadTimeDays < 0 || leadTimeDays > 365) {
            throw new RetailRuleViolation("retail.supplier-invalid", "Lead time must be 0–365 days");
        }
        certifications = certifications == null ? List.of() : List.copyOf(certifications);
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
        scorecard = scorecard == null ? SupplierScorecard.EMPTY : scorecard;
    }

    /** New suppliers start in onboarding until purchasing approves them. */
    public static Supplier onboard(UUID id, String code, String name, String country, String city, String currency,
                                   String paymentTerms, int leadTimeDays, List<String> certifications,
                                   List<SupplierContact> contacts, Instant now) {
        return new Supplier(id, normalise(code), name == null ? null : name.trim(), normalise(country), city,
                currency, paymentTerms, leadTimeDays, certifications, contacts, SupplierStatus.ONBOARDING, null,
                SupplierScorecard.EMPTY, now, now, 0);
    }

    public Supplier update(String name, String city, String currency, String paymentTerms, int leadTimeDays,
                           List<String> certifications, List<SupplierContact> contacts, Instant now) {
        return new Supplier(id, code, name == null ? null : name.trim(), country, city, currency, paymentTerms,
                leadTimeDays, certifications, contacts, status, statusReason, scorecard, createdAt, now, version);
    }

    public Supplier changeStatus(SupplierStatus target, String reason, Instant now) {
        if (!status.next().contains(target)) {
            throw new RetailStateConflict("retail.supplier-invalid-transition",
                    "Supplier " + code + " cannot move from " + status + " to " + target);
        }
        if ((target == SupplierStatus.INACTIVE || target == SupplierStatus.ON_WATCH)
                && (reason == null || reason.isBlank())) {
            throw new RetailRuleViolation("retail.reason-required", "A reason is required to move a supplier to "
                    + target);
        }
        return new Supplier(id, code, name, country, city, currency, paymentTerms, leadTimeDays, certifications,
                contacts, target, reason, scorecard, createdAt, now, version);
    }

    public Supplier recordReceipt(boolean onTime, long units, long defective, Instant now) {
        return new Supplier(id, code, name, country, city, currency, paymentTerms, leadTimeDays, certifications,
                contacts, status, statusReason, scorecard.record(onTime, units, defective), createdAt, now, version);
    }

    /**
     * Who receives purchase orders by email (S225): the first contact with an email whose role mentions sales,
     * orders or purchasing, else the first contact with an email.
     */
    public Optional<SupplierContact> purchaseOrderContact() {
        List<SupplierContact> withEmail = contacts.stream()
                .filter(c -> c.email() != null && !c.email().isBlank()).toList();
        return withEmail.stream()
                .filter(c -> c.role() != null && c.role().toLowerCase(Locale.ROOT).matches(".*(sales|order|purchas).*"))
                .findFirst()
                .or(() -> withEmail.stream().findFirst());
    }

    public void ensureCanReceivePurchaseOrders() {
        if (!status.canReceivePurchaseOrders()) {
            throw new RetailRuleViolation("retail.supplier-inactive", "Supplier " + code + " is " + status
                    + " and cannot receive new purchase orders");
        }
    }

    private static String normalise(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
