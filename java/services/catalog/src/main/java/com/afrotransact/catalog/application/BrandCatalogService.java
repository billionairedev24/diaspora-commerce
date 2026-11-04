package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.brand.BrandStatus;

/**
 * The seller's side of the brand registry: the brands the product form offers for a category, and typing in a brand
 * that is not listed. A brand is only ever offered where its scope covers the chosen category, so a fashion label is
 * never offered for a book or an electronics brand for a dress.
 */
public interface BrandCatalogService {

    /**
     * Brands for the product form's combobox: approved brands whose scope covers the category (or product type) or one
     * of its ancestors, plus the organisation's own pending brands there; the organisation's most used first, then the
     * most popular.
     *
     * @param productType a product-type code (all the categories that use it), or null
     * @param category    a category node id or code (a product type, category or department node), or null; one of
     *                    the two is required
     * @param q           what the seller has typed (accents, case and punctuation do not matter), or null
     */
    BrandChoices choices(String productType, String category, String q, int limit);

    /**
     * Adds a brand the seller typed. When the registry already has one with the same name (ignoring case, accents and
     * punctuation) in this category the existing brand is returned and nothing is created; otherwise a PENDING brand
     * scoped to the product's department is created, usable at once by this organisation and queued for staff review.
     */
    BrandCreated create(String name, String productType, String category);

    /**
     * @param departmentId   the department of the category (the group header "Brands in …")
     * @param departmentName its name, null when the request named only a product type used in several departments
     */
    record BrandChoices(UUID departmentId, String departmentName, List<BrandChoice> brands) {
    }

    /**
     * @param mine        the organisation typed it in (it is PENDING review) or already uses it
     * @param ownProducts the organisation's listings that hold it
     */
    record BrandChoice(UUID id, String name, BrandStatus status, boolean mine, long ownProducts, long products) {
    }

    /** @param created false when the brand already existed and is returned as the match */
    record BrandCreated(BrandChoice brand, boolean created) {
    }
}
