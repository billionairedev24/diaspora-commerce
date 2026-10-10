package com.afrotransact.retail.application;

import java.util.List;

import com.afrotransact.retail.domain.SupplierContact;

/** Supplier master data entered by purchasing. {@code code} is ignored on update. */
public record SupplierCommand(String code, String name, String country, String city, String currency,
                              String paymentTerms, int leadTimeDays, List<String> certifications,
                              List<SupplierContact> contacts) {
}
