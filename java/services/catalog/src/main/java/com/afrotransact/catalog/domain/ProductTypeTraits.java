package com.afrotransact.catalog.domain;

/**
 * What a product type implies for the product form and the bulk upload template.
 *
 * @param food       sold as food: the food section (shelf life, storage, ingredients, allergens) applies
 * @param perishable needs cold storage or a short shelf life (frozen and chilled food)
 * @param regulated  needs a permit, licence or certificate reference (children's products, cosmetics, frozen food)
 * @param preorder   may be sold in a community pre-order window (pre-order eligibility is offered)
 */
public record ProductTypeTraits(boolean food, boolean perishable, boolean regulated, boolean preorder) {

    public static final ProductTypeTraits NONE = new ProductTypeTraits(false, false, false, false);
}
