package com.afrotransact.catalog.domain.barcode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.Gtin;

/**
 * Label rules (S032): retail units carry the manufacturer EAN-13 / UPC-A, unit labels without a vendor GTIN carry an
 * AT-FN code in Code 128, case labels carry GS1-128 with (01) and optional (10) (11) (15) (17) (21), pallet labels
 * carry GS1-128 with (00) SSCC and optional (02) + (37). Explicit symbologies are honoured.
 */
public class DefaultLabelPolicy implements LabelPolicy {

    /** Narrowest bar we print: 0.19 mm (1.5 dots at 203 dpi). Longer data needs a larger label kind. */
    public static final double MIN_MODULE_MM = 0.19;

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final Map<String, String> AI_FIELDS = Map.of("ai00", "sscc", "ai01", "gtin", "ai02", "gtin",
            "ai10", "lot", "ai11", "productionDate", "ai15", "bestBefore", "ai17", "expiry", "ai21", "serial",
            "ai37", "count", "ai", "gtin");

    private final BarcodeEncoder encoder;

    public DefaultLabelPolicy(BarcodeEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public Label build(LabelDraft d) {
        if (d.kind() == null) {
            throw violation("kind", "Required");
        }
        int copies = d.copies() == null ? 1 : d.copies();
        if (copies < 1 || copies > Label.MAX_COPIES) {
            throw violation("copies", "1–" + Label.MAX_COPIES);
        }
        Symbology symbology = d.symbology() != null ? d.symbology() : defaultSymbology(d);
        BarcodeSymbol symbol = switch (symbology) {
            case CODE_128 -> code128(d);
            case EAN_13 -> encoder.ean13(retailGtin(d.gtin()));
            case GS1_128 -> encoder.gs1128(elements(d));
        };
        List<String> lines = new ArrayList<>(d.lines());
        if (symbology == Symbology.GS1_128) {
            String words = dataTitles(d);
            if (!words.isEmpty()) {
                lines.add(words);
            }
        }
        Label label = new Label(d.kind(), symbol, d.caption() != null ? d.caption() : caption(d, symbology),
                d.title(), lines, copies);
        double printable = label.widthMm() - 2 * LabelLayout.margin(label.heightMm());
        if (symbol.totalModules() * MIN_MODULE_MM > printable) {
            throw violation(symbology == Symbology.CODE_128 ? "data" : "kind", "Too long for a " + d.kind()
                    + " label (" + symbol.totalModules() + " modules); shorten the data or use a larger label");
        }
        return label;
    }

    private static Symbology defaultSymbology(LabelDraft d) {
        return switch (d.kind()) {
            case CASE, PALLET -> Symbology.GS1_128;
            case RETAIL -> retailSymbology(d.gtin());
            case UNIT, SHELF, CUSTOM -> blank(d.data()) && !blank(d.gtin()) ? retailSymbology(d.gtin())
                    : Symbology.CODE_128;
        };
    }

    /** EAN-13 for GTIN-12/13 (and GTIN-14 with indicator 0); GS1-128 (01) for GTIN-8 and other GTIN-14s. */
    private static Symbology retailSymbology(String gtin) {
        String g = Gtin.digits(gtin);
        return g.length() == 12 || g.length() == 13 || g.length() == 14 && g.charAt(0) == '0'
                ? Symbology.EAN_13 : Symbology.GS1_128;
    }

    private BarcodeSymbol code128(LabelDraft d) {
        if (blank(d.data())) {
            throw violation("data", "Required for a Code 128 label");
        }
        String text = d.data().strip();
        if (!text.chars().allMatch(c -> c >= 32 && c <= 126)) {
            throw violation("data", "Letters, digits, spaces and ASCII punctuation only");
        }
        return encoder.code128(text);
    }

    private static String retailGtin(String raw) {
        String g = validGtin(raw);
        if (g.length() == 8 || g.length() == 14 && g.charAt(0) != '0') {
            throw violation("gtin", "EAN-13 needs a GTIN-12 or GTIN-13; use GS1_128 for this GTIN");
        }
        return g;
    }

    private static String validGtin(String raw) {
        String g = Gtin.digits(raw);
        if (g.isEmpty()) {
            throw violation("gtin", "Required");
        }
        if (!Gtin.isValid(g)) {
            String fix = Gtin.suggestCorrection(g);
            throw violation("gtin", fix == null ? "Use 8, 12, 13 or 14 digits" : "Invalid check digit (did you mean "
                    + fix + "?)");
        }
        return g;
    }

    private static Gs1ElementString elements(LabelDraft d) {
        Map<ApplicationIdentifier, String> ai = new EnumMap<>(ApplicationIdentifier.class);
        boolean logisticUnit = d.kind() == LabelKind.PALLET || !blank(d.sscc());
        if (logisticUnit) {
            String sscc = Gtin.digits(d.sscc());
            if (sscc.isEmpty()) {
                throw violation("sscc", "Required on a pallet label");
            }
            ai.put(ApplicationIdentifier.SSCC, sscc);
            if (!blank(d.gtin())) {
                ai.put(ApplicationIdentifier.CONTENT, new Gtin(validGtin(d.gtin())).toGtin14());
            }
        } else {
            ai.put(ApplicationIdentifier.GTIN, new Gtin(validGtin(d.gtin())).toGtin14());
        }
        if (!blank(d.lot())) {
            ai.put(ApplicationIdentifier.BATCH_LOT, d.lot().strip());
        }
        putDate(ai, ApplicationIdentifier.PRODUCTION_DATE, d.productionDate());
        putDate(ai, ApplicationIdentifier.BEST_BEFORE, d.bestBefore());
        putDate(ai, ApplicationIdentifier.EXPIRY, d.expiry());
        if (!blank(d.serial())) {
            ai.put(ApplicationIdentifier.SERIAL, d.serial().strip());
        }
        if (d.count() != null) {
            ai.put(ApplicationIdentifier.COUNT, String.valueOf(d.count()));
        }
        try {
            return Gs1ElementString.of(ai);
        } catch (CatalogRuleViolation e) {
            Map<String, String> fields = new LinkedHashMap<>();
            e.fieldErrors().forEach((k, v) -> fields.merge(AI_FIELDS.getOrDefault(k, k), v, (a, b) -> a + "; " + b));
            throw new CatalogRuleViolation(e.getMessage(), fields);
        }
    }

    private static void putDate(Map<ApplicationIdentifier, String> ai, ApplicationIdentifier key, LocalDate date) {
        if (date != null) {
            ai.put(key, date.format(YYMMDD));
        }
    }

    /** Lot, dates, serial and count in words for the text area of case and pallet labels. */
    private static String dataTitles(LabelDraft d) {
        StringJoiner j = new StringJoiner(" · ");
        if (!blank(d.lot())) {
            j.add("LOT " + d.lot().strip());
        }
        if (d.productionDate() != null) {
            j.add("PROD " + d.productionDate());
        }
        if (d.bestBefore() != null) {
            j.add("BEST BEFORE " + d.bestBefore());
        }
        if (d.expiry() != null) {
            j.add("USE BY " + d.expiry());
        }
        if (!blank(d.serial())) {
            j.add("SN " + d.serial().strip());
        }
        if (d.count() != null) {
            j.add("COUNT " + d.count());
        }
        return j.toString();
    }

    private static String caption(LabelDraft d, Symbology symbology) {
        return switch (d.kind()) {
            case UNIT -> symbology == Symbology.CODE_128 ? "AFROTRANSACT UNIT LABEL" : "MANUFACTURER BARCODE";
            case RETAIL -> "MANUFACTURER BARCODE";
            case CASE -> "CASE";
            case PALLET -> "PALLET · SSCC";
            case SHELF, CUSTOM -> null;
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static CatalogRuleViolation violation(String field, String message) {
        return new CatalogRuleViolation("Invalid label: " + field + " — " + message, Map.of(field, message));
    }
}
