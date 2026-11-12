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
                         Set<String> alreadyAccepted) {
        if (!settings.checking()) {
            return new Outcome(Map.of(), images);
        }
        // Each new URL is judged once, for the strictest slot it is used in.
        Map<String, String> slotByUrl = new LinkedHashMap<>();
        boolean mainSeen = false;
        for (ProductImage image : images) {
            if (image.isLabel()) {
                slotByUrl.putIfAbsent(image.url(), LABEL);
            } else if (!mainSeen) {
                mainSeen = true;
                slotByUrl.put(image.url(), MAIN);
            } else {
                slotByUrl.putIfAbsent(image.url(), OTHER);
            }
        }
        for (List<ProductImage> variant : variantImages) {
            variant.forEach(image -> slotByUrl.putIfAbsent(image.url(), OTHER));
        }
        slotByUrl.keySet().removeAll(alreadyAccepted);
        if (slotByUrl.isEmpty()) {
            return new Outcome(Map.of(), images);
        }
        List<ImageVerifier.Item> items = new ArrayList<>();
        slotByUrl.forEach((url, slot) -> items.add(new ImageVerifier.Item(url, slot, productTypeCode)));

        Map<String, ImageCheckResult> results = new LinkedHashMap<>();
        try {
            List<ImageCheckResult> answers = verifier.verify(items);
            for (int i = 0; i < items.size(); i++) {
                results.put(items.get(i).url(), answers.get(i));
            }
        } catch (ImageServiceUnavailable ex) {
            if (settings.enforcing()) {
                throw ex;
            }
            LOG.warn("Image rules (shadow): media could not be asked: {}", ex.getMessage());
            return new Outcome(Map.of(), images);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        int index = 0;
        for (ProductImage image : images) {
            ImageCheckResult r = results.get(image.url());
            if (image.isLabel()) {
                if (r != null && r.blocked()) {
                    errors.put("labelPhoto", r.message());
                }
                continue;
            }
            if (r != null && r.blocked()) {
                errors.putIfAbsent("images[" + index + "]", r.message());
            }
            index++;
        }
        for (int v = 0; v < variantImages.size(); v++) {
            for (ProductImage image : variantImages.get(v)) {
                ImageCheckResult r = results.get(image.url());
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
        return new Outcome(errors, trusted(images, results));
    }

    /** The pictures with media's own size, bytes and type where media knew them: a client's numbers are never kept. */
    private static List<ProductImage> trusted(List<ProductImage> images, Map<String, ImageCheckResult> results) {
        List<ProductImage> out = new ArrayList<>();
        for (ProductImage image : images) {
            ImageCheckResult r = results.get(image.url());
            if (r == null || !r.found() || r.width() <= 0) {
                out.add(image);
            } else {
                out.add(new ProductImage(image.url(), r.width(), r.height(), r.bytes(),
                        r.contentType().isBlank() ? image.contentType() : r.contentType(), image.alt(), image.role()));
            }
        }
        return out;
    }
}
