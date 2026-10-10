package com.afrotransact.retail.domain;

/** A person at the supplier (sales, logistics, quality, accounts receivable). */
public record SupplierContact(String name, String role, String email, String phone) {

    public SupplierContact {
        if (name == null || name.isBlank()) {
            throw new RetailRuleViolation("retail.supplier-invalid", "Contact name is required");
        }
    }
}
