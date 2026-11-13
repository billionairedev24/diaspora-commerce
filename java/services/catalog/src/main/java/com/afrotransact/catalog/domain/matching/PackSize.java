package com.afrotransact.catalog.domain.matching;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The size or pack of a product as a seller or a page writes it ("5kg", "500 g", "2 x 1 kg", "6 pack", "12 fl oz"),
 * reduced to one comparable form: what is in each unit (grams, millilitres), and how many units are in the pack.
 * Two sizes that differ are different products: a 1 kg bag and a 5 kg bag never share a page.
 *
 * @param dimension MASS (grams) or VOLUME (millilitres); null when only a pack count is known
 * @param each      the amount in one unit, in the base unit of {@code dimension}; 0 when unknown
 * @param count     how many units are in the pack (1 when the text names one)
 */
public record PackSize(Dimension dimension, double each, int count) {

    public enum Dimension {
        MASS, VOLUME
    }

    /** What the sizes of a seller's text and of a page say about each other. */
    public enum Agreement {
        SAME, DIFFERENT, UNKNOWN
    }

    /** The size found in a text, and the text without it. */
    public record Found(PackSize size, String rest) {
    }

    private static final double TOLERANCE = 0.01;
    private static final String UNITS = "kgs?|kilograms?|kilos?|grams?|gms?|g|mg|lbs?|pounds?|oz|ounces?|ml|millilit(?:er|re)s?|cl"
            + "|lit(?:er|re)s?|ltrs?|l|fl\\.? ?oz";
    private static final String NUM = "\\d+(?:[.,]\\d+)?";
    private static final Pattern MULTI = Pattern.compile("(?<![\\p{L}\\p{N}])(\\d{1,3})\\s*[x×]\\s*(" + NUM + ")\\s*(" + UNITS
            + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE = Pattern.compile("(?<![\\p{L}\\p{N}.,])(" + NUM + ")\\s*(" + UNITS
            + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACK = Pattern.compile("(?<![\\p{L}\\p{N}])(\\d{1,4})\\s*-?\\s*(?:packs?|pk|pcs|pieces?|"
            + "count|ct|bottles?|cans?|sachets?)(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);

    /** The first size in the text, with the text left over once it is cut out; empty when the text names none. */
    public static Optional<Found> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher multi = MULTI.matcher(text);
        if (multi.find()) {
            Optional<PackSize> each = unit(multi.group(2), multi.group(3), Integer.parseInt(multi.group(1)));
            if (each.isPresent()) {
                return Optional.of(new Found(each.get(), cut(text, multi)));
            }
        }
        Matcher single = SINGLE.matcher(text);
        if (single.find()) {
            Optional<PackSize> each = unit(single.group(1), single.group(2), 1);
            if (each.isPresent()) {
                String rest = cut(text, single);
                Matcher pack = PACK.matcher(rest);
                if (pack.find()) {
                    PackSize with = new PackSize(each.get().dimension, each.get().each, Integer.parseInt(pack.group(1)));
                    return Optional.of(new Found(with, cut(rest, pack)));
                }
                return Optional.of(new Found(each.get(), rest));
            }
        }
        Matcher pack = PACK.matcher(text);
        if (pack.find()) {
            return Optional.of(new Found(new PackSize(null, 0, Integer.parseInt(pack.group(1))), cut(text, pack)));
        }
        return Optional.empty();
    }

    private static String cut(String text, Matcher m) {
        return (text.substring(0, m.start()) + " " + text.substring(m.end())).replaceAll("\\s+", " ").strip();
    }

    private static Optional<PackSize> unit(String number, String unitText, int count) {
        double n;
        try {
            n = Double.parseDouble(number.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (n <= 0 || count < 1) {
            return Optional.empty();
        }
        String u = unitText.toLowerCase(Locale.ROOT).replace(".", "").replace(" ", "");
        double factor;
        Dimension dimension;
        if (u.matches("kgs?|kilograms?|kilos?")) {
            dimension = Dimension.MASS;
            factor = 1000;
        } else if (u.matches("grams?|gms?|g")) {
            dimension = Dimension.MASS;
            factor = 1;
        } else if (u.equals("mg")) {
            dimension = Dimension.MASS;
            factor = 0.001;
        } else if (u.matches("lbs?|pounds?")) {
            dimension = Dimension.MASS;
            factor = 453.592;
        } else if (u.matches("oz|ounces?")) {
            dimension = Dimension.MASS;
            factor = 28.3495;
        } else if (u.equals("floz")) {
            dimension = Dimension.VOLUME;
            factor = 29.5735;
        } else if (u.matches("ml|millilit(er|re)s?")) {
            dimension = Dimension.VOLUME;
            factor = 1;
        } else if (u.equals("cl")) {
            dimension = Dimension.VOLUME;
            factor = 10;
        } else {
            dimension = Dimension.VOLUME;
            factor = 1000;
        }
        return Optional.of(new PackSize(dimension, n * factor, count));
    }

    /** How this size reads against another: the same pack, a different one, or not enough to say. */
    public Agreement against(PackSize other) {
        if (other == null) {
            return Agreement.UNKNOWN;
        }
        if (dimension != null && other.dimension != null) {
            if (dimension != other.dimension || Math.abs(each - other.each) > TOLERANCE * Math.max(each, other.each)) {
                return Agreement.DIFFERENT;
            }
            return count == other.count ? Agreement.SAME : Agreement.DIFFERENT;
        }
        if (dimension == null && other.dimension == null) {
            return count == other.count ? Agreement.SAME : Agreement.DIFFERENT;
        }
        return Agreement.UNKNOWN;
    }

    /** The size in the seller's words for the one-tap question: "5 kg", "330 ml", "6 x 330 ml", "6 pack". */
    public String label() {
        String amount = "";
        if (dimension == Dimension.MASS) {
            amount = each >= 1000 ? trim(each / 1000) + " kg" : trim(each) + " g";
        } else if (dimension == Dimension.VOLUME) {
            amount = each >= 1000 ? trim(each / 1000) + " L" : trim(each) + " ml";
        }
        if (dimension == null) {
            return count + " pack";
        }
        return count > 1 ? count + " x " + amount : amount;
    }

    private static String trim(double v) {
        double rounded = Math.round(v * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** The size in a stable text, for keys: {@code MASS:500000:1}. */
    public String key() {
        return (dimension == null ? "-" : dimension.name()) + ":" + Math.round(each * 100) + ":" + count;
    }
}
