package com.afrotransact.catalog.domain.matching;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.matching.PackSize.Agreement;

/**
 * Selling a different size, weight or pack of a page that already exists (the owner's "I sell it in 1 kg, the page is 5 kg").
 * The seller does not create a duplicate page: they ask for their size to be added as one more variant (its own ATIN) under the
 * page, and staff check it like any other new variant (NEW_VARIANT). This class decides, with no I/O, what a typed size means for
 * a page:
 *
 * <ul>
 *   <li>{@code ALREADY_ON_PAGE}: the page (or one of its variants) already has that size, so there is nothing to ask: the seller
 *       sells that variant;</li>
 *   <li>{@code NEW_SIZE}: a size the page does not have; the label is the canonical spelling ({@code 1 kg}) and the dimension is
 *       the page's own size dimension, or {@code Size} on a page without variations (staff approval introduces it);</li>
 *   <li>{@code SIZE_INVALID}: the text is not a size with a unit or a pack ({@code 1 kg}, {@code 500 ml}, {@code 6 pack});</li>
 *   <li>{@code THEME_UNSUPPORTED}: the page varies by something that is not a size alone (size and colour, length and
 *       pattern): the new combination needs the page owner or staff, the seller adds the product as new instead;</li>
 *   <li>{@code PAGE_SIZE_UNKNOWN}: the page states no size, so there is no size to differ from.</li>
 * </ul>
 */
public final class SizeVariantRules {

    /** The dimension (and theme) a page without variations gets when it is given a second size. */
    public static final String DIMENSION = "Size";

    private static final Pattern SIZE_LIKE = Pattern.compile("(?i)size|weight|volume|net ?content|pack|quantity|capacity");

    private SizeVariantRules() {
    }

    public enum Kind {
        NEW_SIZE, ALREADY_ON_PAGE, SIZE_INVALID, THEME_UNSUPPORTED, PAGE_SIZE_UNKNOWN
    }

    /** A variant of the page as the rules read it. */
    public record PageVariant(String atin, Map<String, String> options) {

        public PageVariant {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    /**
     * @param label       the asked size in the canonical spelling ({@code 1 kg}); null when invalid
     * @param key         the stable key of the size ({@link PackSize#key()}), for "one waiting request per size"
     * @param dimension   the option name the new variant takes ({@code Size})
     * @param existingAtin ALREADY_ON_PAGE: the variant that has the size
     * @param pageLabel   NEW_SIZE on a page without variations: the size the page's only variant has, which becomes its option
     */
    public record Outcome(Kind kind, String label, String key, String dimension, String existingAtin, String pageLabel) {
    }

    /**
     * @param asked         what the seller typed ("1 kg", "500g", "6 pack")
     * @param pageSizeText  the size the page states (its size attribute, else its title); used when it has no variations
     * @param variationTheme the page's theme, empty for a page without variations
     * @param variants      the page's variants
     */
    public static Outcome decide(String asked, String pageSizeText, String variationTheme, List<PageVariant> variants) {
        Optional<PackSize> size = parse(asked);
        if (size.isEmpty()) {
            return new Outcome(Kind.SIZE_INVALID, null, null, null, null, null);
        }
        String label = size.get().label();
        String key = size.get().key();
        if (variants.isEmpty()) {
            return new Outcome(Kind.THEME_UNSUPPORTED, label, key, null, null, null);
        }
        boolean plain = variationTheme == null || variationTheme.isBlank();
        if (plain) {
            PackSize own = PackSize.find(pageSizeText).map(PackSize.Found::size).orElse(null);
            if (own == null) {
                return new Outcome(Kind.PAGE_SIZE_UNKNOWN, label, key, DIMENSION, null, null);
            }
            if (size.get().against(own) == Agreement.SAME) {
                return new Outcome(Kind.ALREADY_ON_PAGE, label, key, DIMENSION, variants.getFirst().atin(), own.label());
            }
            return new Outcome(Kind.NEW_SIZE, label, key, DIMENSION, null, own.label());
        }
        String dimension = sizeDimension(variants);
        if (dimension == null) {
            return new Outcome(Kind.THEME_UNSUPPORTED, label, key, null, null, null);
        }
        for (PageVariant v : variants) {
            String value = v.options().get(dimension);
            if (value != null && (value.strip().equalsIgnoreCase(label) || sameSize(size.get(), value))) {
                return new Outcome(Kind.ALREADY_ON_PAGE, label, key, dimension, v.atin(), null);
            }
        }
        return new Outcome(Kind.NEW_SIZE, label, key, dimension, null, null);
    }

    /** The size in a text that holds nothing but a size: "1 kg" yes, "Tilda basmati 1 kg" no. */
    static Optional<PackSize> parse(String text) {
        Optional<PackSize.Found> found = PackSize.find(text == null ? null : text.strip());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        boolean leftover = found.get().rest().chars().anyMatch(Character::isLetterOrDigit);
        return leftover ? Optional.empty() : Optional.of(found.get().size());
    }

    private static boolean sameSize(PackSize asked, String value) {
        return PackSize.find(value).map(f -> asked.against(f.size()) == Agreement.SAME).orElse(false);
    }

    /** The one option every variant has and that is a size; null when the page varies by anything else or by two things. */
    private static String sizeDimension(List<PageVariant> variants) {
        Map<String, String> first = variants.getFirst().options();
        if (first.size() != 1) {
            return null;
        }
        String name = first.keySet().iterator().next();
        boolean everyone = variants.stream().allMatch(v -> v.options().size() == 1 && v.options().containsKey(name));
        return everyone && SIZE_LIKE.matcher(name.toLowerCase(Locale.ROOT)).find() ? name : null;
    }
}
