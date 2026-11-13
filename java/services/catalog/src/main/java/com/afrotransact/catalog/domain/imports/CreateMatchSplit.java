package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.Gtin;

/**
 * Splits the rows of a CREATE upload by what the match step found (S393): a row whose barcode matches a page that somebody
 * else owns is an offer row, not a new page; the rest are created as before. Rows of one parent group stay together unless
 * the group is mixed: matched children become offers and the children that are not on the page yet become NEW_VARIANT
 * requests for the page, never a second page. A seller's own page is not matched here (the barcode check says so).
 */
public final class CreateMatchSplit {

    private CreateMatchSplit() {
    }

    /**
     * @param rest     rows to create as products
     * @param offers   rows that become offers on the existing page their barcode names
     * @param variants rows of a mixed group whose child is not on the page: filed as NEW_VARIANT requests
     * @param matched  the matched target of every offer row, by row number
     */
    public record Result(List<SheetRow> rest, List<SheetRow> offers, List<SheetRow> variants,
                         Map<Integer, OfferTarget> matched) {

        public Result {
            rest = List.copyOf(rest);
            offers = List.copyOf(offers);
            variants = List.copyOf(variants);
            matched = Map.copyOf(matched);
        }

        public boolean any() {
            return !offers.isEmpty() || !variants.isEmpty();
        }
    }

    /** The barcode keys of the sheet's rows, so the caller can match them. */
    public static List<TargetKey> keysOf(ParsedSheet sheet) {
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.OFFER));
        Integer gtin = index.get(ImportColumns.GTIN);
        List<TargetKey> out = new ArrayList<>();
        if (gtin == null) {
            return out;
        }
        for (SheetRow r : sheet.rows()) {
            String digits = Gtin.digits(r.cell(gtin));
            if (!digits.isEmpty() && Gtin.isValid(digits)) {
                out.add(TargetKey.of(digits, null, null, null));
            }
        }
        return out;
    }

    public static Result split(ParsedSheet sheet, Map<String, OfferTarget> targets, String ownerOrg) {
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.OFFER));
        Integer gtin = index.get(ImportColumns.GTIN);
        int parent = parentColumn(sheet.header());
        Map<String, List<SheetRow>> groups = new LinkedHashMap<>();
        for (SheetRow r : sheet.rows()) {
            String p = parent < 0 ? "" : r.cell(parent).strip().toUpperCase(Locale.ROOT);
            groups.computeIfAbsent(p.isEmpty() ? "row:" + r.rowNumber() : "parent:" + p, k -> new ArrayList<>()).add(r);
        }
        List<SheetRow> rest = new ArrayList<>();
        List<SheetRow> offers = new ArrayList<>();
        List<SheetRow> variants = new ArrayList<>();
        Map<Integer, OfferTarget> matched = new LinkedHashMap<>();
        for (List<SheetRow> group : groups.values()) {
            List<SheetRow> hits = new ArrayList<>();
            List<SheetRow> misses = new ArrayList<>();
            for (SheetRow r : group) {
                OfferTarget target = gtin == null ? null : targets.get("gtin:" + Gtin.digits(r.cell(gtin)));
                if (target != null && target.kind() == OfferTarget.Kind.MATCH && target.atin() != null
                        && !ownerOrg.equals(target.ownerOrg())) {
                    hits.add(r);
                    matched.put(r.rowNumber(), target);
                } else {
                    misses.add(r);
                }
            }
            if (hits.isEmpty()) {
                rest.addAll(group);
            } else {
                offers.addAll(hits);
                variants.addAll(misses);
            }
        }
        return new Result(sorted(rest), sorted(offers), sorted(variants), matched);
    }

    private static List<SheetRow> sorted(List<SheetRow> rows) {
        return rows.stream().sorted((a, b) -> Integer.compare(a.rowNumber(), b.rowNumber())).toList();
    }

    /** The column of the Parent SKU, -1 when the file has none. */
    public static int parentColumn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String h = ImportColumns.normalise(header.get(i)).replace('_', ' ');
            if (h.equals("parent sku")) {
                return i;
            }
        }
        return -1;
    }
}
