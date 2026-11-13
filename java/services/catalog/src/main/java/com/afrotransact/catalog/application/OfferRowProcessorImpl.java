package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.imports.OfferImportContext;
import com.afrotransact.catalog.domain.imports.OfferImportResult;
import com.afrotransact.catalog.domain.imports.OfferImportValidator;
import com.afrotransact.catalog.domain.imports.OfferRow;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.PriceQtyResult;
import com.afrotransact.catalog.domain.imports.PriceQtyRow;
import com.afrotransact.catalog.domain.imports.PriceQtyValidator;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.port.ImportMetrics;
import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.catalog.port.OfferWriter.OfferTerms;
import com.afrotransact.catalog.port.OfferWriter.PriceStock;
import com.afrotransact.catalog.port.OfferWriter.Result;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductImportRepository.AuditRow;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class OfferRowProcessorImpl implements OfferRowProcessor {

    private static final String APPROVAL_CODE = "offers.selling-approval-required";
    private static final String NO_CHANGE = "No change.";

    private final ProductImportRepository imports;
    private final ImportMatcher matcher;
    private final OfferWriter offers;
    private final ImportMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    OfferRowProcessorImpl(ProductImportRepository imports, ImportMatcher matcher, OfferWriter offers,
                          ImportMetrics metrics, Clock clock, PlatformTransactionManager transactions) {
        this.imports = imports;
        this.matcher = matcher;
        this.offers = offers;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    private static ParsedSheet sheetOf(ProductImport u, List<StoredRow> rows) {
        return new ParsedSheet(u.header(), rows.stream().map(r -> new SheetRow(r.rowNumber(), r.cells())).toList());
    }

    @Override
    public List<RowResult> offers(ProductImport u, List<StoredRow> rows) {
        ParsedSheet sheet = sheetOf(u, rows);
        Map<String, OfferTarget> targets = matcher.resolve(u.owner().orgId(), OfferImportValidator.keysOf(sheet));
        OfferImportResult checked = OfferImportValidator.validate(sheet, new OfferImportContext(targets, Integer.MAX_VALUE));
        Map<Integer, ValidatedRow> validated = new HashMap<>();
        checked.validation().rows().forEach(r -> validated.put(r.rowNumber(), r));
        Map<Integer, OfferRow> typed = new HashMap<>();
        checked.importable().forEach(r -> typed.put(r.rowNumber(), r));

        Map<Integer, RowResult> done = new LinkedHashMap<>();
        List<OfferTerms> toSend = new ArrayList<>();
        Map<String, OfferRow> sent = new HashMap<>();
        for (StoredRow stored : rows) {
            OfferRow row = typed.get(stored.rowNumber());
            if (row == null) {
                ValidatedRow v = validated.get(stored.rowNumber());
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.FAILED, v == null
                        ? "The row could not be read again" : truncate(v.summary()), null, null, null));
            } else if (row.held()) {
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.HELD, row.heldNote(),
                        row.atin(), null, null));
            } else {
                OfferTerms terms = terms(row);
                toSend.add(terms);
                sent.put(terms.rowKey(), row);
            }
        }
        if (!toSend.isEmpty()) {
            for (Result r : offers.upsert(u.owner().orgId(), u.id(), u.uploadedBy(), toSend)) {
                OfferRow row = sent.get(r.rowKey());
                if (row != null) {
                    done.put(row.rowNumber(), fromOffers(row.rowNumber(), r, row.atin()));
                }
            }
        }
        return ordered(rows, done);
    }

    @Override
    public List<RowResult> feed(ProductImport u, List<StoredRow> rows) {
        PriceQtyResult checked = PriceQtyValidator.validate(sheetOf(u, rows), Integer.MAX_VALUE);
        Map<Integer, PriceQtyRow> typed = new HashMap<>();
        checked.importable().forEach(r -> typed.put(r.rowNumber(), r));
        Map<Integer, ValidatedRow> validated = new HashMap<>();
        checked.validation().rows().forEach(r -> validated.put(r.rowNumber(), r));

        Map<Integer, RowResult> done = new LinkedHashMap<>();
        List<PriceStock> toSend = new ArrayList<>();
        for (StoredRow stored : rows) {
            PriceQtyRow row = typed.get(stored.rowNumber());
            if (row == null) {
                ValidatedRow v = validated.get(stored.rowNumber());
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.FAILED, v == null
                        ? "The row could not be read again" : truncate(v.summary()), null, null, null));
            } else if (row.priceMinor() == null && row.listPriceMinor() == null && row.quantity() == null) {
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.SKIPPED,
                        "Nothing to change on this row.", row.atin(), null, null));
            } else {
                toSend.add(new PriceStock(Integer.toString(row.rowNumber()), row.sku(), row.atin(), row.priceMinor(),
                        row.listPriceMinor(), row.quantity()));
            }
        }
        if (!toSend.isEmpty()) {
            for (Result r : offers.setPricesAndStock(u.owner().orgId(), u.id(), u.uploadedBy(), toSend)) {
                int row = Integer.parseInt(r.rowKey());
                done.put(row, fromOffers(row, r, r.atin()));
            }
        }
        return ordered(rows, done);
    }

    private static List<RowResult> ordered(List<StoredRow> rows, Map<Integer, RowResult> done) {
        return rows.stream().map(r -> done.getOrDefault(r.rowNumber(), new RowResult(r.rowNumber(), RowOutcome.FAILED,
                "offers-service gave no answer for this row. Upload the file again.", null, null, null))).toList();
    }

    private static OfferTerms terms(OfferRow r) {
        return new OfferTerms(Integer.toString(r.rowNumber()), r.atin(), r.sku(), r.condition().name(), r.priceMinor(),
                r.listPriceMinor(), r.shippingMinor(), r.fulfilment() == FulfilmentMode.AFROTRANSACT ? "AFROTRANSACT"
                        : "SELLER", r.quantity(), r.handlingDays(),
                r.fulfilment() == FulfilmentMode.AFROTRANSACT ? null : new OfferWriter.ShipFrom(blankToNull(r.shipCity()),
                        blankToNull(r.shipRegion()), r.shipCountry(), blankToNull(r.shipZip())),
                r.conditionNote(), r.box() == null ? null : new OfferWriter.Box(r.box().lengthIn(), r.box().widthIn(),
                        r.box().heightIn(), r.box().weightLb()));
    }

    /**
     * What offers-service said, as the row's outcome. A refusal for the seller's missing selling approval is HELD, not a
     * failure: the seller can be approved, and nothing is for sale meanwhile. An offer waiting for the tax decision is HELD.
     */
    private static RowResult fromOffers(int rowNumber, Result r, String atin) {
        String at = r.atin() != null ? r.atin() : atin;
        return switch (r.outcome()) {
            case "CREATED" -> new RowResult(rowNumber, RowOutcome.OFFER_CREATED, null, at, r.offerId(), null);
            case "UPDATED" -> new RowResult(rowNumber, RowOutcome.UPDATED, null, at, r.offerId(), null);
            case "UNCHANGED" -> new RowResult(rowNumber, RowOutcome.SKIPPED, NO_CHANGE, at, r.offerId(), null);
            case "HELD" -> new RowResult(rowNumber, RowOutcome.HELD, truncate(r.message()), at, r.offerId(), null);
            default -> APPROVAL_CODE.equals(r.code())
                    ? new RowResult(rowNumber, RowOutcome.HELD, truncate(r.message() + " This row is held and is not for "
                            + "sale."), at, null, null)
                    : new RowResult(rowNumber, RowOutcome.FAILED, truncate(r.message()), at, null, null);
        };
    }

    @Override
    public void save(UUID importId, List<RowResult> results) {
        tx.executeWithoutResult(s -> saveLocked(importId, results));
    }

    private void saveLocked(UUID importId, List<RowResult> results) {
        ProductImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return;
        }
        Set<Integer> pending = imports.pendingAmong(importId, results.stream().map(RowResult::rowNumber).toList());
        List<RowResult> fresh = results.stream().filter(r -> pending.contains(r.rowNumber())).toList();
        if (fresh.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        imports.setRowResults(importId, fresh);
        List<AuditRow> audit = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int failed = 0;
        int held = 0;
        for (RowResult r : fresh) {
            metrics.row(u.mode().name(), r.outcome().name());
            switch (r.outcome()) {
                case OFFER_CREATED -> {
                    created++;
                    audit.add(new AuditRow(r.rowNumber(), "OFFER_CREATED", r.atin(), null, r.offerId()));
                }
                case UPDATED -> {
                    updated++;
                    audit.add(new AuditRow(r.rowNumber(), "UPDATED", r.atin(), null, r.offerId()));
                }
                case HELD -> {
                    held++;
                    audit.add(new AuditRow(r.rowNumber(), "HELD", r.atin(), null, r.offerId()));
                }
                case FAILED -> failed++;
                default -> {
                }
            }
        }
        imports.audit(importId, u.owner().orgId(), u.uploadedBy(), now, audit);
        imports.update(u.progressed(fresh.size(), 0, updated, failed, created, held, now));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 999) + "…" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
