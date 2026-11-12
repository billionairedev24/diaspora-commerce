package com.afrotransact.offers.domain;

import java.util.Set;
import java.util.TreeSet;

/**
 * The request carried fields an offer does not have (HTTP 422). Page content (title, images, attributes, brand, category)
 * belongs to the product page, owned by whoever created it; a seller who sells the page changes it by suggesting an edit.
 * Any other unknown field is refused too, so a typo never silently does nothing.
 */
public class PageContentLocked extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The words of page content a seller might send by mistake; every one is refused with the page message. */
    public static final Set<String> CONTENT_FIELDS = Set.of("title", "name", "description", "bullets", "bulletPoints",
            "attributes", "brand", "brandId", "category", "categoryId", "productType", "productTypeCode",
            "variationTheme", "variationOptions", "options", "images", "image", "imageUrl", "imageUrls", "mainImage",
            "photos", "taxFacts", "sizeChart", "fitNote");

    /** What the seller reads. */
    public static final String MESSAGE = "That detail belongs to the product page. Suggest an edit instead.";

    private final transient Set<String> contentFields;
    private final transient Set<String> otherFields;

    public PageContentLocked(Set<String> sent) {
        super(MESSAGE);
        Set<String> content = new TreeSet<>();
        Set<String> other = new TreeSet<>();
        for (String field : sent) {
            (CONTENT_FIELDS.contains(field) ? content : other).add(field);
        }
        this.contentFields = Set.copyOf(content);
        this.otherFields = Set.copyOf(other);
    }

    /** Fields that are page content (refused with {@link #MESSAGE}). */
    public Set<String> contentFields() {
        return new TreeSet<>(contentFields);
    }

    /** Fields an offer does not know at all. */
    public Set<String> otherFields() {
        return new TreeSet<>(otherFields);
    }

    /** True when at least one field is page content. */
    public boolean aboutPageContent() {
        return !contentFields.isEmpty();
    }

    /** Refuses the request when it carried any field an offer does not have. */
    public static void refuse(Set<String> sent) {
        if (sent != null && !sent.isEmpty()) {
            throw new PageContentLocked(sent);
        }
    }
}
