package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentifierServiceImpl implements IdentifierService {

    private final ProductRepository products;
    private final ActorResolver actors;
    private final CatalogSettingsService settings;

    IdentifierServiceImpl(ProductRepository products, ActorResolver actors, CatalogSettingsService settings) {
        this.products = products;
        this.actors = actors;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public GtinCheck check(String raw, boolean isbnRequired) {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
        GtinReader.Reading reading = GtinReader.read(raw, settings.current().reservedPrefixes());
        String digits = reading.digits();
        switch (reading.status()) {
            case EMPTY:
                return new GtinCheck(digits, null, "INVALID", false, null, null,
                        isbnRequired ? "ISBN-13 required" : "Required, or request exemption");
            case INVALID:
                return new GtinCheck(digits, null, "INVALID", false, reading.suggestion(), null, reading.message());
            case RESTRICTED:
                return new GtinCheck(digits, reading.gtin14(), "RESTRICTED", false, null, null, reading.message());
            default:
                break;
        }
        if (isbnRequired && !new Gtin(digits).isIsbn13()) {
            return new GtinCheck(digits, reading.gtin14(), "INVALID", false, null, null, "Must be ISBN-13 (978/979)");
        }
        String existing = products.findByVariantGtin(digits).filter(p -> GtinPageVisibility.visibleTo(p, actor))
                .map(p -> p.atin().value()).orElse(null);
        return new GtinCheck(digits, reading.gtin14(), existing == null ? "NO_MATCH" : "MATCH", true, null, existing,
                existing == null ? "Valid" : "Matches existing product " + existing + " — add your offer to it");
    }
}
