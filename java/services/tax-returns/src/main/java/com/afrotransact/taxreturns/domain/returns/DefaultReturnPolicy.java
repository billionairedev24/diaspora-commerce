package com.afrotransact.taxreturns.domain.returns;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default policy.
 *
 * <ul>
 *   <li>Window: seller policy (seller-fulfilled) → product-type window → platform default (30 days) after delivery.</li>
 *   <li>Perishables and non-returnable product types: "report a problem" with a seller-fault reason, refund without
 *       return.</li>
 *   <li>Low-value lines (remaining value ≤ threshold) may be refunded without return.</li>
 *   <li>Driver pickup only where the order came by AfroTransact Delivery.</li>
 *   <li>AfroTransact-fulfilled returns auto-approve; problem reports and keep-the-item refunds above the low-value
 *       threshold wait for staff. Seller-fulfilled requests wait for the seller until the SLA, except low-value
 *       keep-the-item refunds.</li>
 * </ul>
 */
public final class DefaultReturnPolicy implements ReturnPolicy {

    public static final String NOT_DELIVERED = "not-delivered";
    public static final String WINDOW_CLOSED = "window-closed";
    public static final String ALREADY_RETURNED = "already-returned";
    private static final int MAX_COMMENT = 500;

    private final ReturnPolicySettings settings;

    public DefaultReturnPolicy(ReturnPolicySettings settings) {
        this.settings = settings;
    }

    @Override
    public LineEligibility eligibility(DeliveredLine line, FulfilmentType fulfilment, DeliveryMethod delivery,
                                       Optional<SellerReturnPolicy> seller, Instant now) {
        if (line.deliveredAt() == null) {
            return ineligible(line, null, NOT_DELIVERED);
        }
        Instant windowEnds = line.deliveredAt().plus(Duration.ofDays(windowDays(line, fulfilment, seller)));
        if (now.isAfter(windowEnds)) {
            return ineligible(line, windowEnds, WINDOW_CLOSED);
        }
        int remaining = line.remainingQuantity();
        if (remaining == 0) {
            return ineligible(line, windowEnds, ALREADY_RETURNED);
        }
        boolean problemOnly = nonReturnable(line);
        boolean sellerFaultOnly = !fulfilment.afroTransactFulfilled()
                && seller.map(p -> !p.acceptsReturns()).orElse(false);
        Set<ReturnMethod> methods = EnumSet.noneOf(ReturnMethod.class);
        if (problemOnly) {
            methods.add(ReturnMethod.NO_RETURN);
        } else {
            methods.add(ReturnMethod.DROP_OFF);
            if (delivery == DeliveryMethod.AFROTRANSACT_DELIVERY) {
                methods.add(ReturnMethod.DRIVER_PICKUP);
            }
            if (Math.multiplyExact(line.unitPriceMinor(), (long) remaining) <= settings.lowValueThresholdMinor()) {
                methods.add(ReturnMethod.NO_RETURN);
            }
        }
        return new LineEligibility(line.lineId(), true, problemOnly, sellerFaultOnly, remaining, windowEnds, methods,
                null);
    }

    @Override
    public void validate(ReturnMethod method, List<ReturnLineRequest> lines, Map<String, LineEligibility> eligibility,
                         Map<String, DeliveredLine> delivered) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (method == null) {
            errors.put("method", "Choose how the items come back");
        }
        if (lines == null || lines.isEmpty()) {
            errors.put("lines", "Pick at least one item");
            throw new ReturnRuleViolation("returns.invalid-request", "The return request is incomplete", errors);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ReturnLineRequest r = lines.get(i);
            String f = "lines[" + i + "]";
            LineEligibility e = r.lineId() == null ? null : eligibility.get(r.lineId());
            if (e == null || !delivered.containsKey(r.lineId())) {
                errors.put(f + ".lineId", "This item is not part of the shipment");
                continue;
            }
            if (!seen.add(r.lineId())) {
                errors.put(f + ".lineId", "Each item can only be listed once");
                continue;
            }
            if (!e.eligible()) {
                errors.put(f + ".lineId", switch (e.ineligibleCode()) {
                    case NOT_DELIVERED -> "This item has not been delivered yet";
                    case WINDOW_CLOSED -> "The return window for this item has closed";
                    default -> "This item has already been returned";
                });
                continue;
            }
            if (r.quantity() < 1 || r.quantity() > e.maxQuantity()) {
                errors.put(f + ".quantity", "Return between 1 and " + e.maxQuantity());
            }
            if (r.reason() == null) {
                errors.put(f + ".reason", "Pick a reason");
            } else {
                if ((e.reportProblemOnly() || e.sellerFaultOnly()) && !r.reason().sellerFault()) {
                    errors.put(f + ".reason", e.reportProblemOnly()
                            ? "Food and other perishables can only be reported as damaged, wrong, spoiled or missing"
                            : "This seller only accepts returns of damaged, defective or wrong items");
                }
                if (r.reason().evidenceRequired() && r.evidenceAssetIds().isEmpty()) {
                    errors.put(f + ".evidenceAssetIds", "Add at least one photo");
                }
            }
            if (r.evidenceAssetIds().size() > settings.maxEvidence()) {
                errors.put(f + ".evidenceAssetIds", "At most " + settings.maxEvidence() + " photos");
            }
            if (r.evidenceAssetIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 120)) {
                errors.put(f + ".evidenceAssetIds", "Invalid media asset id");
            }
            if (r.comment() != null && r.comment().length() > MAX_COMMENT) {
                errors.put(f + ".comment", "At most " + MAX_COMMENT + " characters");
            }
            if (method != null && !e.methods().contains(method)) {
                errors.put("method", methodMessage(method, e));
            }
        }
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("returns.not-eligible", "Some items can't be returned this way", errors);
        }
    }

    @Override
    public boolean autoApprove(FulfilmentType fulfilment, ReturnMethod method, long estimateMinor) {
        if (method == ReturnMethod.NO_RETURN) {
            return estimateMinor <= settings.lowValueThresholdMinor();
        }
        return fulfilment.afroTransactFulfilled();
    }

    @Override
    public Instant approvalDueAt(Instant requestedAt) {
        return requestedAt.plus(settings.sellerApprovalSla());
    }

    @Override
    public boolean refundAtFirstScan(FulfilmentType fulfilment, ReturnMethod method, long estimateMinor,
                                     Optional<SellerReturnPolicy> seller) {
        if (method == ReturnMethod.NO_RETURN || estimateMinor > settings.firstScanRefundMaxMinor()) {
            return false;
        }
        return fulfilment.afroTransactFulfilled() || seller.map(SellerReturnPolicy::refundAtFirstScan).orElse(false);
    }

    @Override
    public int restockingFeeBps(FulfilmentType fulfilment, Optional<SellerReturnPolicy> seller) {
        if (fulfilment.afroTransactFulfilled()) {
            return 0;
        }
        return Math.min(settings.maxRestockingFeeBps(), seller.map(SellerReturnPolicy::restockingFeeBps).orElse(0));
    }

    @Override
    public int dropOffDays() {
        return settings.dropOffDays();
    }

    private int windowDays(DeliveredLine line, FulfilmentType fulfilment, Optional<SellerReturnPolicy> seller) {
        if (!fulfilment.afroTransactFulfilled() && seller.isPresent()) {
            return seller.get().windowDays();
        }
        Integer byType = line.productType() == null ? null : settings.windowDaysByProductType().get(line.productType());
        return byType != null ? byType : settings.defaultWindowDays();
    }

    private boolean nonReturnable(DeliveredLine line) {
        return line.perishable()
                || line.productType() != null && settings.nonReturnableProductTypes().contains(line.productType());
    }

    private static LineEligibility ineligible(DeliveredLine line, Instant windowEnds, String code) {
        return new LineEligibility(line.lineId(), false, false, false, 0, windowEnds, Set.of(), code);
    }

    private static String methodMessage(ReturnMethod method, LineEligibility e) {
        return switch (method) {
            case DRIVER_PICKUP -> "Driver pickup is only available for orders delivered by AfroTransact Delivery";
            case NO_RETURN -> "Only low-value items can be refunded without sending them back";
            case DROP_OFF -> e.reportProblemOnly() ? "Perishables are not sent back; report a problem instead"
                    : "Drop-off is not available for this item";
        };
    }
}
