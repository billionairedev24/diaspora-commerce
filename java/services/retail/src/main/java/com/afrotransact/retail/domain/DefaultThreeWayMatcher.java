package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Line rules, worst outcome wins:
 * <ul>
 *   <li>EXCEPTION — line not on the PO, invoice currency ≠ PO currency, unit price outside ±tolerance of the PO
 *       price, invoiced amount outside ±tolerance of PO price × invoiced qty, more invoiced than ordered, or more
 *       invoiced than received once receiving is complete.</li>
 *   <li>HOLD — more invoiced than received while goods are still due, or open shortage/damage claims.</li>
 *   <li>MATCH — otherwise.</li>
 * </ul>
 * Expected amounts are PO price × received quantity (capped at what is still uninvoiced).
 */
public class DefaultThreeWayMatcher implements ThreeWayMatcher {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public MatchResult match(PurchaseOrder po, String currency, List<InvoiceLine> invoiceLines, long openClaims,
                             MatchTolerance tolerance) {
        List<MatchLine> lines = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean exception = false;
        boolean hold = false;
        if (!currency.equals(po.currency())) {
            exception = true;
            reasons.add("Invoice currency " + currency + " differs from PO currency " + po.currency());
        }
        Set<Integer> seen = new HashSet<>();
        long expectedTotal = 0;
        long invoicedTotal = 0;
        for (InvoiceLine il : invoiceLines) {
            if (!seen.add(il.poLineNo())) {
                exception = true;
                reasons.add("PO line " + il.poLineNo() + " is invoiced twice on the same invoice");
                continue;
            }
            PoLine pl = po.lines().stream().filter(l -> l.lineNo() == il.poLineNo()).findFirst().orElse(null);
            if (pl == null) {
                exception = true;
                reasons.add("Invoice line for PO line " + il.poLineNo() + " is not on " + po.number());
                lines.add(new MatchLine(il.poLineNo(), null, 0, 0, 0, il.qty(), 0, il.unitPriceMinor(), 0,
                        il.amountMinor(), null, MatchLineStatus.NOT_ON_PO));
                invoicedTotal += il.amountMinor();
                continue;
            }
            int uninvoicedReceived = Math.max(0, pl.qtyReceived() - pl.qtyInvoiced());
            long expected = Math.multiplyExact((long) Math.min(il.qty(), uninvoicedReceived), pl.unitCostMinor());
            long priceAtPo = Math.multiplyExact((long) il.qty(), pl.unitCostMinor());
            BigDecimal priceVariance = percent(il.unitPriceMinor() - pl.unitCostMinor(), pl.unitCostMinor());
            MatchLineStatus status;
            if (priceVariance.abs().compareTo(tolerance.pricePercent()) > 0) {
                status = MatchLineStatus.PRICE_VARIANCE;
                reasons.add("Line " + pl.lineNo() + " (" + pl.sku() + "): price " + priceVariance
                        + "% vs PO, tolerance ±" + tolerance.pricePercent() + "%");
            } else if (percent(il.amountMinor() - priceAtPo, priceAtPo).abs().compareTo(tolerance.pricePercent()) > 0) {
                status = MatchLineStatus.AMOUNT_VARIANCE;
                reasons.add("Line " + pl.lineNo() + " (" + pl.sku() + "): amount outside tolerance");
            } else if (pl.qtyInvoiced() + il.qty() > pl.qtyOrdered()) {
                status = MatchLineStatus.QTY_OVER_ORDERED;
                reasons.add("Line " + pl.lineNo() + " (" + pl.sku() + "): invoiced " + (pl.qtyInvoiced() + il.qty())
                        + " but ordered " + pl.qtyOrdered());
            } else if (il.qty() > uninvoicedReceived) {
                if (po.receivingComplete()) {
                    status = MatchLineStatus.QTY_OVER_RECEIVED;
                    reasons.add("Line " + pl.lineNo() + " (" + pl.sku() + "): invoiced " + il.qty()
                            + " but only " + uninvoicedReceived + " received and not yet invoiced");
                } else {
                    status = MatchLineStatus.AWAITING_RECEIPT;
                    reasons.add("Line " + pl.lineNo() + " (" + pl.sku() + "): awaiting goods receipt for "
                            + (il.qty() - uninvoicedReceived) + " units");
                }
            } else {
                status = MatchLineStatus.MATCH;
            }
            if (status == MatchLineStatus.AWAITING_RECEIPT) {
                hold = true;
            } else if (status != MatchLineStatus.MATCH) {
                exception = true;
            }
            lines.add(new MatchLine(pl.lineNo(), pl.sku(), pl.qtyOrdered(), pl.qtyReceived(), pl.qtyInvoiced(),
                    il.qty(), pl.unitCostMinor(), il.unitPriceMinor(), expected, il.amountMinor(), priceVariance,
                    status));
            expectedTotal += expected;
            invoicedTotal += il.amountMinor();
        }
        if (openClaims > 0) {
            hold = true;
            reasons.add(openClaims + " open receiving claim(s) on " + po.number() + " — payment held until settled");
        }
        MatchOutcome outcome = exception ? MatchOutcome.EXCEPTION : hold ? MatchOutcome.HOLD : MatchOutcome.MATCH;
        return new MatchResult(outcome, lines, expectedTotal, invoicedTotal,
                expectedTotal == 0 ? null : percent(invoicedTotal - expectedTotal, expectedTotal), reasons);
    }

    private static BigDecimal percent(long diff, long base) {
        if (base == 0) {
            return diff == 0 ? BigDecimal.ZERO.setScale(2) : BigDecimal.valueOf(100).setScale(2);
        }
        return BigDecimal.valueOf(diff).multiply(HUNDRED).divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_UP);
    }
}
