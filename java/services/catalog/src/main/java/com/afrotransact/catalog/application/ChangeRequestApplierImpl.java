package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ChangeRequestPolicy;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ChangeRequestApplierImpl implements ChangeRequestApplier {

    static final String NEEDS_TAX_REVIEW = "catalog.change-request-needs-tax-review";
    static final String IMAGE_REJECTED = "catalog.change-request-image-rejected";
    static final String IMAGE_HELD = "catalog.change-request-image-held";

    private static final Logger LOG = LoggerFactory.getLogger(ChangeRequestApplierImpl.class);

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final ProductEvents events;
    private final ImageVerifier verifier;
    private final ImageSettings imageSettings;

    ChangeRequestApplierImpl(ProductRepository products, CategoryRepository categories, BrandRepository brands,
                             ProductEvents events, ImageVerifier verifier, ImageSettings imageSettings) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.events = events;
        this.verifier = verifier;
        this.imageSettings = imageSettings;
    }

    @Override
    public Product apply(ProductChangeRequest r, Product page, Instant now) {
        Map<String, Object> payload = r.payload();
        Product next = switch (r.type()) {
            case TITLE -> page.withContent(ChangeRequestPolicy.text(payload, "value"), page.attributes(), page.images(),
                    page.details(), now);
            case DESCRIPTION -> page.withContent(page.title(), page.attributes(), page.images(),
                    page.details().withDescription(ChangeRequestPolicy.text(payload, "value")), now);
            case ATTRIBUTE -> page.withContent(page.title(), withAttribute(page, payload), page.images(),
                    page.details(), now);
            case BRAND -> page.withContent(page.title(), withBrand(page, ChangeRequestPolicy.text(payload, "value")),
                    page.images(), page.details(), now);
            case CATEGORY -> inCategory(page, ChangeRequestPolicy.text(payload, "categoryId"), now);
            case IMAGE_ADD -> page.withContent(page.title(), page.attributes(), withImage(page, r, false), page.details(),
                    now);
            case IMAGE_REPLACE -> page.withContent(page.title(), page.attributes(), withImage(page, r, true),
                    page.details(), now);
            default -> throw new BusinessRuleException("catalog.change-request-not-a-content-change",
                    "A " + r.type() + " request is resolved by staff in another way, not applied to the page text");
        };
        Product saved = products.update(next);
        events.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        return saved;
    }

    /** One more detail of the page. Brand has its own request, and a detail that is a tax fact needs tax review. */
    private Map<String, String> withAttribute(Product page, Map<String, Object> payload) {
        String key = ChangeRequestPolicy.text(payload, "key");
        if (key.equals(BrandKeys.NAME) || key.equals(BrandKeys.ID) || key.equals(BrandKeys.REASON)) {
            throw new BusinessRuleException("catalog.change-request-wrong-type",
                    "The brand is changed with a BRAND request, not as a detail");
        }
        ProductType type = categories.findProductType(page.productTypeCode()).orElse(null);
        // A tax fact is not one of the plain details (ProductType.attribute leaves it out): look at it first.
        if (type != null && type.taxFacts().stream().anyMatch(a -> a.key().equals(key))) {
            throw new BusinessRuleException(NEEDS_TAX_REVIEW, "'" + key + "' decides how the item is taxed, so a change "
                    + "goes through tax review, not through a page edit");
        }
        AttributeDefinition definition = type == null ? null : type.attribute(key).orElse(null);
        if (definition == null) {
            throw new BusinessRuleException("catalog.change-request-unknown-detail",
                    "'" + key + "' is not a detail of this kind of product");
        }
        Map<String, String> next = new LinkedHashMap<>(page.attributes());
        next.put(key, definition.normalise(ChangeRequestPolicy.text(payload, "value")));
        return next;
    }

    private Map<String, String> withBrand(Product page, String name) {
        Brand brand = brands.findLiveByKey(BrandNames.normalise(name)).stream()
                .filter(b -> b.status() == BrandStatus.APPROVED).findFirst()
                .orElseThrow(() -> new BusinessRuleException("catalog.change-request-brand-unknown",
                        "That brand is not in the brand list yet. Ask for the brand to be added first."));
        Map<String, String> next = new LinkedHashMap<>(page.attributes());
        next.put(BrandKeys.NAME, brand.name());
        next.put(BrandKeys.ID, brand.id().toString());
        next.remove(BrandKeys.REASON);
        return next;
    }

    /** Only to a category with the same tax profile: another profile changes the tax outcome, which tax review decides. */
    private Product inCategory(Product page, String categoryId, Instant now) {
        CategoryNode target = categories.findById(UUID.fromString(categoryId)).filter(CategoryNode::active)
                .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE)
                .orElseThrow(() -> new BusinessRuleException("catalog.change-request-category-unknown",
                        "That category does not exist or cannot be chosen"));
        CategoryNode current = page.categoryId() == null ? null : categories.findById(page.categoryId()).orElse(null);
        if (current == null || target.taxProfileId() == null || !target.taxProfileId().equals(current.taxProfileId())) {
            throw new BusinessRuleException(NEEDS_TAX_REVIEW, "That category is taxed differently from the page's "
                    + "category, so moving the page goes through tax review, not through a page edit");
        }
        return page.withCategory(target.id(), target.productTypeCode(), now);
    }

    private List<ProductImage> withImage(Product page, ProductChangeRequest r, boolean replace) {
        ProductImage asked = ChangeRequestPolicy.imageOf(r.payload());
        if (!asked.allowedType() || asked.bytes() > ProductImage.MAX_BYTES || asked.width() <= 0 || asked.height() <= 0
                || !asked.url().startsWith("https://")) {
            throw new BusinessRuleException(IMAGE_REJECTED, "This picture does not pass the image checks");
        }
        List<ProductImage> pictures = new ArrayList<>(page.images().stream().filter(i -> !i.isLabel()).toList());
        List<ProductImage> labels = page.images().stream().filter(ProductImage::isLabel).toList();
        int position = replace ? ChangeRequestPolicy.number(r.payload(), "position") : -1;
        if (replace && (position < 0 || position >= pictures.size())) {
            throw new BusinessRuleException(IMAGE_REJECTED, "There is no picture at position " + position);
        }
        if (!replace && pictures.size() >= ProductDraftPolicy.MAX_IMAGES) {
            throw new BusinessRuleException(IMAGE_REJECTED, "The page already has " + ProductDraftPolicy.MAX_IMAGES
                    + " pictures");
        }
        boolean main = replace ? position == 0 : "MAIN".equals(r.field());
        ProductImage image = judged(asked, main, page.productTypeCode());
        if (replace) {
            pictures.set(position, image);
        } else if (main) {
            pictures.addFirst(image);
        } else {
            pictures.add(image);
        }
        pictures.addAll(labels);
        return pictures;
    }

    /**
     * The picture as media judges it for the slot it takes (S390), never on the width and height the seller sent. A
     * picture media rejects is refused with media's plain message. One held for staff stays in the image review queue and
     * the request cannot be applied until that is decided (409). While the image rules are enforced, media being
     * unreachable also refuses, so a picture is never applied unchecked.
     */
    private ProductImage judged(ProductImage asked, boolean main, String productTypeCode) {
        if (!imageSettings.checking()) {
            return asked;
        }
        ImageCheckResult result;
        try {
            result = verifier.verify(List.of(new ImageVerifier.Item(asked.url(), main ? "MAIN" : "OTHER",
                    productTypeCode))).getFirst();
        } catch (ImageServiceUnavailable ex) {
            if (imageSettings.enforcing()) {
                throw ex;
            }
            LOG.warn("Image rules (shadow): media could not be asked about a change request picture: {}", ex.getMessage());
            return asked;
        }
        if (result.blocked() && !imageSettings.enforcing()) {
            LOG.info("Image rules (shadow): a change request picture would be refused ({})", result.verdict());
            return asked;
        }
        if (result.blocked()) {
            if (result.found() && "HOLD".equals(result.verdict())) {
                throw new ConflictException(IMAGE_HELD, "This photo is waiting in the held image review queue. Decide "
                        + "it there first, then approve this request. " + result.message());
            }
            throw new BusinessRuleException(IMAGE_REJECTED, result.message());
        }
        if (!result.found() || result.width() <= 0) {
            return asked;
        }
        return new ProductImage(asked.url(), result.width(), result.height(), result.bytes(),
                result.contentType().isBlank() ? asked.contentType() : result.contentType(), asked.alt(), asked.role());
    }
}
