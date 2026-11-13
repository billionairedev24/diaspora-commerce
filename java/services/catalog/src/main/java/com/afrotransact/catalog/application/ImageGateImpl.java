package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ImageGateImpl implements ImageGate {

    static final String MAIN = "MAIN";
    static final String OTHER = "OTHER";
    static final String LABEL = "LABEL";

    private static final Logger LOG = LoggerFactory.getLogger(ImageGateImpl.class);

    private final ImageVerifier verifier;
    private final ImageSettings settings;

    ImageGateImpl(ImageVerifier verifier, ImageSettings settings) {
        this.verifier = verifier;
        this.settings = settings;
    }

    @Override
    public Outcome check(List<ProductImage> images, List<List<ProductImage>> variantImages, String productTypeCode,
                         Set<String> alreadyAccepted, String ownerOrgId) {
        Map<String, String> refErrors = new LinkedHashMap<>();
        for (int v = 0; v < variantImages.size(); v++) {
            if (variantImages.get(v).stream().anyMatch(ProductImage::isHeld)) {
                refErrors.put("variants[" + v + "].images",
                        "Use a photo that is already published for a variant picture, or put this one in the main pictures.");
            }
        }
        boolean refs = images.stream().anyMatch(ProductImage::isHeld);
        if (!settings.checking() && !refs) {
            return new Outcome(refErrors, images);
        }
        // Each new URL (or held photo id) is judged once, for the strictest slot it is used in.
        Map<String, String> slotByRef = new LinkedHashMap<>();
        boolean mainSeen = false;
        for (ProductImage image : images) {
            if (image.isLabel()) {
                slotByRef.putIfAbsent(ref(image), LABEL);
            } else if (!mainSeen) {
                mainSeen = true;
                slotByRef.put(ref(image), MAIN);
            } else {
                slotByRef.putIfAbsent(ref(image), OTHER);
            }
        }
        for (List<ProductImage> variant : variantImages) {
            variant.stream().filter(image -> !image.isHeld()).forEach(image -> slotByRef.putIfAbsent(ref(image), OTHER));
        }
        slotByRef.keySet().removeAll(alreadyAccepted);
        if (!settings.checking()) {
            // Not checking the rules: only the held photos are resolved, because they cannot be stored without it.
            Set<String> heldRefs = new LinkedHashSet<>();
            images.stream().filter(ProductImage::isHeld).forEach(i -> heldRefs.add(ref(i)));
            slotByRef.keySet().retainAll(heldRefs);
        }
        if (slotByRef.isEmpty()) {
            return new Outcome(refErrors, images);
        }
        Map<String, ProductImage> byRef = new LinkedHashMap<>();
        images.forEach(i -> byRef.putIfAbsent(ref(i), i));
        List<ImageVerifier.Item> items = new ArrayList<>();
        slotByRef.forEach((key, slot) -> {
            ProductImage held = byRef.get(key);
            items.add(held != null && held.isHeld()
                    ? new ImageVerifier.Item("", slot, productTypeCode, held.assetId(), ownerOrgId)
                    : new ImageVerifier.Item(key, slot, productTypeCode));
        });

        Map<String, ImageCheckResult> results = new LinkedHashMap<>();
        try {
            List<ImageCheckResult> answers = verifier.verify(items);
            for (int i = 0; i < items.size(); i++) {
                results.put(refOf(items.get(i)), answers.get(i));
            }
        } catch (ImageServiceUnavailable ex) {
            if (settings.enforcing() || refs) {
                throw ex;
            }
            LOG.warn("Image rules (shadow): media could not be asked: {}", ex.getMessage());
            return new Outcome(refErrors, images);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> hard = new LinkedHashMap<>(refErrors);
        boolean held = false;
        int index = 0;
        for (ProductImage image : images) {
            ImageCheckResult r = results.get(ref(image));
            if (image.isHeld()) {
                String field = image.isLabel() ? "labelPhoto" : "images[" + index + "]";
                if (r != null && r.held()) {
                    held = true;
                } else if (r == null || !r.ready()) {
                    // Rejected, missing or someone else's: it can never be stored, whatever the mode.
                    hard.putIfAbsent(field, r == null ? ImageCheckResult.unknown(image.url()).message() : r.message());
                } else if (r.blocked()) {
                    errors.putIfAbsent(field, r.message());
                }
            } else if (image.isLabel()) {
                if (r != null && r.blocked()) {
                    errors.put("labelPhoto", r.message());
                }
            } else if (r != null && r.blocked()) {
                errors.putIfAbsent("images[" + index + "]", r.message());
            }
            if (!image.isLabel()) {
                index++;
            }
        }
        for (int v = 0; v < variantImages.size(); v++) {
            for (ProductImage image : variantImages.get(v)) {
                ImageCheckResult r = results.get(ref(image));
                if (r != null && r.blocked()) {
                    errors.putIfAbsent("variants[" + v + "].images", r.message());
                }
            }
        }
        if (!settings.enforcing()) {
            if (!errors.isEmpty()) {
                LOG.info("Image rules (shadow): {} of {} new images would be refused ({})", errors.size(),
                        items.size(), new LinkedHashSet<>(errors.keySet()));
            }
            errors = new LinkedHashMap<>();
        }
        errors.putAll(hard);
        return new Outcome(errors, trusted(images, results), held);
    }

    @Override
    public Resolved resolveHeld(List<ProductImage> images, String ownerOrgId) {
        List<ImageVerifier.Item> items = new ArrayList<>();
        for (ProductImage image : images) {
            if (image.isHeld()) {
                items.add(new ImageVerifier.Item("", image.isLabel() ? LABEL : OTHER, null, image.assetId(), ownerOrgId));
            }
        }
        if (items.isEmpty()) {
            return new Resolved(images, false, false);
        }
        List<ImageCheckResult> answers = verifier.verify(items);
        Map<String, ImageCheckResult> byId = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byId.put(items.get(i).assetId(), answers.get(i));
        }
        boolean stillHeld = false;
        boolean rejected = false;
        List<ProductImage> out = new ArrayList<>();
        for (ProductImage image : images) {
            ImageCheckResult r = image.isHeld() ? byId.get(image.assetId()) : null;
            if (r == null) {
                out.add(image);
            } else if (r.held()) {
                stillHeld = true;
                out.add(image);
            } else if (r.ready()) {
                out.add(image.published(r.url(), r.width(), r.height(), r.bytes(), r.contentType()));
            } else {
                rejected = true;
                out.add(image);
            }
        }
        return new Resolved(out, stillHeld, rejected);
    }

    /** What a picture is asked about: its asset id while held (no URL yet), else its public URL. */
    private static String ref(ProductImage image) {
        return image.isHeld() ? "asset:" + image.assetId() : image.url();
    }

    private static String refOf(ImageVerifier.Item item) {
        return item.assetId() != null ? "asset:" + item.assetId() : item.url();
    }

    /** The pictures with media's own size, bytes and type where media knew them: a client's numbers are never kept. */
    private static List<ProductImage> trusted(List<ProductImage> images, Map<String, ImageCheckResult> results) {
        List<ProductImage> out = new ArrayList<>();
        for (ProductImage image : images) {
            ImageCheckResult r = results.get(ref(image));
            if (image.isHeld()) {
                // Cleared by media: it is an ordinary picture now. Still held (or refused): it stays a reference.
                out.add(r != null && r.ready()
                        ? image.published(r.url(), r.width(), r.height(), r.bytes(), r.contentType()) : image);
            } else if (r == null || !r.found() || r.width() <= 0) {
                out.add(image);
            } else {
                out.add(new ProductImage(image.url(), r.width(), r.height(), r.bytes(),
                        r.contentType().isBlank() ? image.contentType() : r.contentType(), image.alt(), image.role()));
            }
        }
        return out;
    }
}
