package com.afrotransact.catalog.domain.tax;

import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;

/** The product cannot go live because one of its photos is still held for review by media; it can be saved meanwhile. */
public class ImageHeld extends CatalogRuleViolation {

    private static final long serialVersionUID = 1L;

    public ImageHeld(String message) {
        super(message, Map.of("images", "We are checking your photo."));
    }
}
