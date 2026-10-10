package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.port.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentifierServiceImpl implements IdentifierService {

    private final ProductRepository products;

    IdentifierServiceImpl(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public GtinCheck check(String raw, boolean isbnRequired) {
        String digits = Gtin.digits(raw);
        if (digits.isEmpty()) {
            return new GtinCheck(digits, false, null, null, isbnRequired ? "ISBN-13 required" : "Required, or request exemption");
        }
        if (!Gtin.isValid(digits)) {
            String fix = Gtin.suggestCorrection(digits);
            return new GtinCheck(digits, false, fix, null,
                    fix == null ? "Use 8, 12, 13 or 14 digits" : "Invalid check digit");
        }
        if (isbnRequired && !new Gtin(digits).isIsbn13()) {
            return new GtinCheck(digits, false, null, null, "Must be ISBN-13 (978/979)");
        }
        String existing = products.findByVariantGtin(digits).map(p -> p.atin().value()).orElse(null);
        return new GtinCheck(digits, true, null, existing,
                existing == null ? "Valid" : "Matches existing product " + existing + " — add your offer to it");
    }
}
