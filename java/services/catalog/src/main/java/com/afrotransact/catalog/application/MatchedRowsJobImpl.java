package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.afrotransact.catalog.domain.imports.CreateMatchSplit;
import com.afrotransact.catalog.domain.imports.OfferImportValidator;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.TargetKey;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import org.springframework.stereotype.Component;

@Component
class MatchedRowsJobImpl implements MatchedRowsJob {

    private final ProductImportRepository imports;
    private final OfferRowProcessor offers;
    private final VariantRequests variants;
    private final ImportMatcher matcher;

    MatchedRowsJobImpl(ProductImportRepository imports, OfferRowProcessor offers, VariantRequests variants,
                       ImportMatcher matcher) {
        this.imports = imports;
        this.offers = offers;
        this.variants = variants;
        this.matcher = matcher;
    }

    @Override
    public void apply(ProductImport u, String groupKey, List<StoredRow> rows) {
        List<StoredRow> pending = rows.stream()
                .filter(r -> r.outcome() == RowOutcome.PENDING && r.status() != RowStatus.ERROR).toList();
        if (pending.isEmpty()) {
            return;
        }
        List<RowResult> results;
        if (groupKey.startsWith(SKIP_GROUP)) {
            results = pending.stream().map(r -> new RowResult(r.rowNumber(), RowOutcome.SKIPPED,
                    "No change: this row was imported from an earlier file.", null, null, null)).toList();
        } else if (groupKey.startsWith(OFFER_GROUP)) {
            results = offers.offers(u, pending);
        } else {
            String parentAtin = parentAtinOf(u, pending.getFirst());
            results = new ArrayList<>();
            for (StoredRow row : pending) {
                results.add(variants.file(u, row, parentAtin));
            }
        }
        offers.save(u.id(), results);
    }

    /** The page the row's matched siblings (same Parent SKU) are on: the target of the first sibling that became an offer. */
    private String parentAtinOf(ProductImport u, StoredRow row) {
        int parentCol = CreateMatchSplit.parentColumn(u.header());
        String parent = parentCol < 0 || parentCol >= row.cells().size() || row.cells().get(parentCol) == null ? ""
                : row.cells().get(parentCol).strip().toUpperCase(Locale.ROOT);
        if (parent.isEmpty()) {
            return null;
        }
        List<StoredRow> siblings = imports.allRows(u.id()).stream().filter(r -> r.groupKey().startsWith(OFFER_GROUP))
                .filter(r -> parentCol < r.cells().size() && r.cells().get(parentCol) != null
                        && r.cells().get(parentCol).strip().toUpperCase(Locale.ROOT).equals(parent)).toList();
        if (siblings.isEmpty()) {
            return null;
        }
        ParsedSheet sheet = new ParsedSheet(u.header(),
                siblings.stream().map(r -> new SheetRow(r.rowNumber(), r.cells())).toList());
        for (TargetKey key : OfferImportValidator.keysOf(sheet)) {
            OfferTarget target = matcher.resolve(u.owner().orgId(), List.of(key)).get(key.id());
            if (target != null && target.parentAtin() != null) {
                return target.parentAtin();
            }
        }
        return null;
    }
}
