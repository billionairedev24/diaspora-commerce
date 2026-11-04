package com.afrotransact.catalog.domain;

import java.util.Optional;

import com.afrotransact.catalog.domain.sizing.SizingTrait;

/**
 * What a product type implies for the product form and the bulk upload template.
 *
 * @param food       sold as food: the food section (shelf life, storage, ingredients, allergens) applies
 * @param perishable needs cold storage or a short shelf life (frozen and chilled food)
 * @param regulated  needs a permit, licence or certificate reference (children's products, cosmetics, frozen food)
 * @param preorder   may be sold in a community pre-order window (pre-order eligibility is offered)
 * @param sizing     the product type is sized: sellers attach a size chart and write their own size labels; null for
 *                   types that are not sized
 */
public record ProductTypeTraits(boolean food, boolean perishable, boolean regulated, boolean preorder,
                                SizingTrait sizing) {

    public static final ProductTypeTraits NONE = new ProductTypeTraits(false, false, false, false);

    /** Traits of a type that is not sized. */
    public ProductTypeTraits(boolean food, boolean perishable, boolean regulated, boolean preorder) {
        this(food, perishable, regulated, preorder, null);
    }

    /** These traits with another sizing trait (null = not sized). */
    public ProductTypeTraits withSizing(SizingTrait next) {
        return new ProductTypeTraits(food, perishable, regulated, preorder, next);
    }

    public Optional<SizingTrait> sizingTrait() {
        return Optional.ofNullable(sizing);
    }
}
