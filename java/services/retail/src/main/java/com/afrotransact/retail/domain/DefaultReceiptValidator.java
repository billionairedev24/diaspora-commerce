package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The design's validateReceipt rules: lot 4–20 letters/digits/dashes and new for the SKU; made ≤ today; expiry after
 * made and not passed; remaining life ≥ max(90 days, 50% of shelf life) or the batch is quarantined; bin A-00-00
 * matching the storage type; QA recorded; more than 2% over the expected quantity is blocked until approved.
 */
public class DefaultReceiptValidator implements ReceiptValidator {

    private final int minRemainingDays;
    private final double minRemainingFraction;
    private final BigDecimal overTolerancePercent;

    public DefaultReceiptValidator(int minRemainingDays, double minRemainingFraction,
                                   BigDecimal overTolerancePercent) {
        this.minRemainingDays = minRemainingDays;
        this.minRemainingFraction = minRemainingFraction;
        this.overTolerancePercent = overTolerancePercent;
    }

    @Override
    public ReceiptValidation validate(GoodsReceipt receipt, Map<UUID, RetailItem> items, Set<String> existingLots,
                                      LocalDate today) {
        List<LineCheck> checks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReceiptLine l : receipt.lines()) {
            checks.add(check(l, items.get(l.itemId()), existingLots, seen, today));
        }
        Map<Integer, int[]> byPoLine = new LinkedHashMap<>();
        Map<Integer, String> skus = new LinkedHashMap<>();
        for (ReceiptLine l : receipt.lines()) {
            int[] acc = byPoLine.computeIfAbsent(l.poLineNo(), k -> new int[3]);
            acc[0] += l.expectedQty();
            acc[1] += l.received();
            acc[2] += l.qtyDamaged();
            skus.putIfAbsent(l.poLineNo(), l.sku());
        }
        List<LineVariance> variances = new ArrayList<>();
        byPoLine.forEach((poLine, acc) -> variances.add(new LineVariance(poLine, skus.get(poLine), acc[0], acc[1],
                acc[2], !receipt.overReceiptApproved() && overBeyondTolerance(acc[0], acc[1]))));
        List<String> receiptErrors = new ArrayList<>();
        if (receipt.lines().stream().mapToInt(ReceiptLine::received).sum() == 0) {
            receiptErrors.add("Nothing has been counted yet");
        }
        boolean ok = receiptErrors.isEmpty() && checks.stream().allMatch(c -> c.errors().isEmpty())
                && variances.stream().noneMatch(LineVariance::overBlocked);
        return new ReceiptValidation(checks, variances, receiptErrors, ok);
    }

    @Override
    public List<Discrepancy> discrepancies(GoodsReceipt receipt, ReceiptValidation validation) {
        List<Discrepancy> out = new ArrayList<>();
        for (LineVariance v : validation.variances()) {
            if (v.diff() < 0) {
                out.add(new Discrepancy(claimRef(receipt, out), DiscrepancyType.SHORT, v.poLineNo(), v.sku(),
                        v.expected(), v.received(), -v.diff(), "SUPPLIER", ClaimStatus.OPEN,
                        "Short " + (-v.diff()) + " against " + (receipt.shipmentRef() == null ? receipt.poNumber()
                                : receipt.shipmentRef())));
            } else if (v.diff() > 0) {
                out.add(new Discrepancy(claimRef(receipt, out), DiscrepancyType.OVER, v.poLineNo(), v.sku(),
                        v.expected(), v.received(), v.diff(), null, ClaimStatus.NOT_REQUIRED,
                        receipt.overReceiptApproved() ? "Over-receipt approved by " + receipt.overApprovedBy()
                                : "Within over-receipt tolerance"));
            }
            if (v.damaged() > 0) {
                out.add(new Discrepancy(claimRef(receipt, out), DiscrepancyType.DAMAGED, v.poLineNo(), v.sku(),
                        v.expected(), v.received(), v.damaged(), "CARRIER", ClaimStatus.OPEN,
                        v.damaged() + " damaged in transit → -DMG quarantine lot"));
            }
        }
        return out;
    }

    private static String claimRef(GoodsReceipt receipt, List<Discrepancy> sofar) {
        return "CLM-" + receipt.number() + "-" + (sofar.size() + 1);
    }

    private boolean overBeyondTolerance(int expected, int received) {
        BigDecimal limit = BigDecimal.valueOf(expected)
                .multiply(BigDecimal.valueOf(100).add(overTolerancePercent)).divide(BigDecimal.valueOf(100));
        return BigDecimal.valueOf(received).compareTo(limit) > 0;
    }

    private LineCheck check(ReceiptLine l, RetailItem item, Set<String> existingLots, Set<String> seen,
                            LocalDate today) {
        List<String> e = new ArrayList<>();
        List<String> w = new ArrayList<>();
        Disposition disposition = Disposition.SELLABLE;
        Integer remaining = null;
        if (l.received() == 0) {
            return new LineCheck(l.lineNo(), e, w, disposition, null);
        }
        if (item == null) {
            e.add("Unknown item " + l.sku());
            return new LineCheck(l.lineNo(), e, w, disposition, null);
        }
        if (l.lotNo() == null) {
            e.add("Batch / lot number is required");
        } else if (!LotRules.validLot(l.lotNo())) {
            e.add("Lot must be 4–20 letters, digits or dashes");
        } else {
            String key = ReceiptValidator.lotKey(l.sku(), l.lotNo());
            if (existingLots.contains(key)) {
                e.add("Lot " + l.lotNo() + " already exists for this SKU");
            } else if (!seen.add(key)) {
                e.add("Lot " + l.lotNo() + " appears twice on this receipt; merge the lines");
            }
        }
        if (l.mfgDate() == null) {
            e.add("Manufacture date is required");
        }
        if (l.expDate() == null) {
            e.add("Expiry date is required");
        }
        if (l.mfgDate() != null && l.expDate() != null && !l.expDate().isAfter(l.mfgDate())) {
            e.add("Expiry must be after manufacture date");
        }
        if (l.mfgDate() != null && l.mfgDate().isAfter(today)) {
            e.add("Manufacture date is in the future");
        }
        if (l.expDate() != null) {
            remaining = (int) ChronoUnit.DAYS.between(today, l.expDate());
            int min = item.minRemainingLifeDays(minRemainingDays, minRemainingFraction);
            if (remaining < 0) {
                e.add("Already expired — reject at dock");
            } else if (remaining < min) {
                w.add("Short-dated: " + remaining + " days left, minimum " + min + " — goes to quarantine");
                disposition = Disposition.QUARANTINE;
            }
        }
        if (l.qtyGood() > 0) {
            if (l.bin() == null || !LotRules.BIN.matcher(l.bin()).matches()) {
                e.add("Put-away bin must look like R-01-02");
            } else if (item.storage().binPrefix() != null && !l.bin().startsWith(item.storage().binPrefix())) {
                e.add(item.storage() + " items go to " + item.storage().binPrefix() + " bins");
            }
            if (l.qa() == QaResult.PENDING) {
                e.add("QA inspection result needed");
            }
        }
        if (l.qa() == QaResult.FAIL) {
            w.add("QA failed — whole batch quarantined");
            disposition = Disposition.QUARANTINE;
        }
        if (!l.scanned() && l.lotNo() != null) {
            w.add("Keyed manually — case label not scanned");
        }
        if (l.lotNo() != null && l.asnLotNo() != null && !l.lotNo().equals(l.asnLotNo()) && !l.split()) {
            w.add("Lot differs from supplier ASN (" + l.asnLotNo() + ")");
        }
        if (l.qtyDamaged() > 0) {
            w.add(l.qtyDamaged() + " damaged → separate quarantine lot + carrier claim");
        }
        return new LineCheck(l.lineNo(), e, w, disposition, remaining);
    }
}
