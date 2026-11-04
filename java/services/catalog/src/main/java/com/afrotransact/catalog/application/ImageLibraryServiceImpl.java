package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.imports.ImageLibrary;
import com.afrotransact.catalog.domain.imports.LibraryImage;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ImageLibraryServiceImpl implements ImageLibraryService {

    private static final Logger LOG = LoggerFactory.getLogger(ImageLibraryServiceImpl.class);

    private final ProductRepository products;
    private final ProductWriter writer;
    private final ImageLibraryLookup library;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final TransactionTemplate tx;

    ImageLibraryServiceImpl(ProductRepository products, ProductWriter writer, ImageLibraryLookup library,
                            PermissionChecker permissions, ResourceAccessChecker access, OwnerResolver owners,
                            PlatformTransactionManager transactions) {
        this.products = products;
        this.writer = writer;
        this.library = library;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public AttachResult attach(List<String> skus) {
        ListingOwner owner = owner(CatalogRelations.ORG_MANAGE_PRODUCTS);
        Set<String> wanted = upper(skus);
        List<Product> listings = products.findByOwnerOrg(owner.orgId()).stream()
                .filter(p -> wanted.isEmpty() || p.variants().stream().anyMatch(v -> wanted.contains(key(v))))
                .toList();
        Set<String> all = new LinkedHashSet<>();
        listings.forEach(p -> p.variants().forEach(v -> all.add(key(v))));
        ImageLibrary lib = library.lookup(owner.orgId(), all, List.of());
        int updated = 0;
        int attached = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (Product p : listings) {
            String sku = p.variants().isEmpty() ? "" : p.variants().getFirst().sellerSku();
            List<ProductImage> images = productImages(p, lib);
            int count = images == null ? 0 : images.size();
            List<VariantDraft> variants = new ArrayList<>();
            boolean variantChanged = false;
            for (Variant v : p.variants()) {
                List<ProductImage> own = v.images();
                if (p.variants().size() > 1) {
                    LibraryImage main = lib.slot(v.sellerSku(), "MAIN").filter(LibraryImage::ready).orElse(null);
                    if (main != null && (own.isEmpty() || !own.getFirst().url().equals(main.url()))) {
                        own = List.of(main.toProductImage());
                        variantChanged = true;
                        count++;
                    }
                }
                variants.add(new VariantDraft(v.options(), v.sellerSku(), v.gtin(), v.gtinExempt(), v.priceMinor(),
                        v.quantity(), v.compareAtMinor(), own));
            }
            if (images == null && !variantChanged) {
                continue;
            }
            if (p.status() == ProductStatus.TAKEN_DOWN) {
                skipped.add(new Skipped(sku, "The listing is taken down"));
                continue;
            }
            if (images != null && !images.getFirst().largeEnoughForMain()) {
                skipped.add(new Skipped(sku, "The main image is under " + ProductImage.MIN_MAIN_EDGE + " × "
                        + ProductImage.MIN_MAIN_EDGE + " px"));
                continue;
            }
            List<ProductImage> next = images == null ? p.images() : images;
            UpdateProductCommand cmd = new UpdateProductCommand(null, p.title(), p.attributes(), next,
                    p.variationTheme(), variants, p.preorderEligible(), p.shipsNow(), p.details(), p.version(), null);
            try {
                tx.executeWithoutResult(s -> writer.update(p, cmd));
                updated++;
                attached += count;
            } catch (RuntimeException ex) {
                LOG.info("Library images not attached to {}: {}", p.id(), ex.getMessage());
                skipped.add(new Skipped(sku, ex.getMessage() == null ? "Refused" : ex.getMessage()));
            }
        }
        return new AttachResult(updated, attached, List.copyOf(skipped));
    }

    /** The ready MAIN/PT images of the first of the product's SKUs that has any, or null when none or unchanged. */
    private static List<ProductImage> productImages(Product p, ImageLibrary lib) {
        for (Variant v : p.variants()) {
            List<LibraryImage> found = lib.productImages(v.sellerSku()).stream().filter(LibraryImage::ready).toList();
            if (found.isEmpty() || !"MAIN".equals(found.getFirst().slot())) {
                continue;
            }
            List<ProductImage> images = found.stream().map(LibraryImage::toProductImage).toList();
            List<String> now = p.images().stream().map(ProductImage::url).toList();
            List<String> next = images.stream().map(ProductImage::url).toList();
            return now.equals(next) ? null : images;
        }
        return null;
    }

    @Override
    public Usage usage(List<String> urls, List<String> skus) {
        ListingOwner owner = owner(CatalogRelations.ORG_VIEW_PRODUCTS);
        Set<String> wantedUrls = new LinkedHashSet<>(urls == null ? List.of() : urls);
        Set<String> wantedSkus = upper(skus);
        Map<String, Set<String>> byUrl = new LinkedHashMap<>();
        List<String> known = new ArrayList<>();
        for (Product p : products.findByOwnerOrg(owner.orgId())) {
            List<String> productSkus = p.variants().stream().map(Variant::sellerSku).toList();
            for (ProductImage img : p.images()) {
                if (wantedUrls.contains(img.url())) {
                    byUrl.computeIfAbsent(img.url(), u -> new LinkedHashSet<>()).addAll(productSkus);
                }
            }
            for (Variant v : p.variants()) {
                if (wantedSkus.contains(key(v))) {
                    known.add(v.sellerSku());
                }
                for (ProductImage img : v.images()) {
                    if (wantedUrls.contains(img.url())) {
                        byUrl.computeIfAbsent(img.url(), u -> new LinkedHashSet<>()).add(v.sellerSku());
                    }
                }
            }
        }
        List<ImageUse> usage = byUrl.entrySet().stream()
                .map(e -> new ImageUse(e.getKey(), List.copyOf(e.getValue()))).toList();
        return new Usage(usage, List.copyOf(known));
    }

    private ListingOwner owner(String relation) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, relation, owner.orgId());
        return owner;
    }

    private static String key(Variant v) {
        return v.sellerSku() == null ? "" : v.sellerSku().toUpperCase(Locale.ROOT);
    }

    private static Set<String> upper(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank())
                    .forEach(v -> out.add(v.strip().toUpperCase(Locale.ROOT)));
        }
        return out;
    }
}
