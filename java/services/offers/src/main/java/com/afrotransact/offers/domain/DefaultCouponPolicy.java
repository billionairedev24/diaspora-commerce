package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Default coupon rules. Checks run in a fixed order so the reported reason is stable: status, window, currency,
 * clip, total limit, per-customer limit, first order, eligible lines, minimum spend (on eligible lines only), then
 * the discount. PERCENT rounds down and respects the cap; FIXED never exceeds the eligible subtotal; FREE_SHIPPING
 * discounts the cart's shipping. Discounts are allocated to lines pro rata (remainder to the largest lines first) so
 * seller-funded amounts can be charged to the right payout.
 */
public class DefaultCouponPolicy implements CouponPolicy {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{3,20}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @Override
    public void validate(Coupon c) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (c.code() == null || !CODE.matcher(c.code()).matches()) {
            errors.put("code", "3–20 letters or digits");
        }
        if (c.type() == null) {
            errors.put("type", "Required");
        } else {
            switch (c.type()) {
                case PERCENT -> {
                    if (c.value() < 1 || c.value() > 100) {
                        errors.put("value", "A percentage between 1 and 100");
                    }
                }
                case FIXED -> {
                    if (c.value() <= 0) {
                        errors.put("value", "An amount greater than zero");
                    }
                }
                case FREE_SHIPPING -> {
                    if (c.value() != 0) {
                        errors.put("value", "Free shipping coupons have no value");
                    }
                }
            }
            if (c.maxDiscountMinor() != null && (c.type() != CouponType.PERCENT || c.maxDiscountMinor() <= 0)) {
                errors.put("maxDiscountMinor", "Only percentage coupons take a positive cap");
            }
        }
        if (c.currency() == null || !CURRENCY.matcher(c.currency()).matches()) {
            errors.put("currency", "ISO 4217 code such as USD");
        }
        if (c.scope() == null) {
            errors.put("scope", "Required");
        } else if (c.scope().needsRef() && (c.scopeRef() == null || c.scopeRef().isBlank())) {
            errors.put("scopeRef", "Required for scope " + c.scope());
        }
        if (c.minSpendMinor() < 0) {
            errors.put("minSpendMinor", "Cannot be negative");
        }
        if (c.totalLimit() < 0) {
            errors.put("totalLimit", "Cannot be negative (0 = unlimited)");
        }
        if (c.perCustomerLimit() < 0) {
            errors.put("perCustomerLimit", "Cannot be negative (0 = unlimited)");
        }
        if (c.totalLimit() > 0 && c.perCustomerLimit() > c.totalLimit()) {
            errors.put("perCustomerLimit", "Cannot exceed the total limit");
        }
        if (c.funding() == null) {
            errors.put("funding", "Required");
        } else if (c.funding() == CouponFunding.SELLER && (c.fundedByOrg() == null || c.fundedByOrg().isBlank())) {
            errors.put("fundedByOrg", "Seller-funded coupons name the funding seller");
        } else if (c.funding() == CouponFunding.SELLER && c.scope() == CouponScope.SELLER
                && !c.fundedByOrg().equals(c.scopeRef())) {
            errors.put("scopeRef", "A seller-funded coupon can only target the funding seller");
        }
        if (c.startsAt() == null) {
            errors.put("startsAt", "Required");
        } else if (c.endsAt() != null && !c.endsAt().isAfter(c.startsAt())) {
            errors.put("endsAt", "Must be after the start");
        }
        if (!errors.isEmpty()) {
            throw new OfferRuleViolation("The coupon is not valid", errors);
        }
    }

    @Override
    public CouponEvaluation evaluate(Coupon c, CouponCart cart, CouponUsage usage, Instant now) {
        if (c.status() != CouponStatus.ACTIVE) {
            return CouponEvaluation.rejected(CouponRejection.NOT_ACTIVE, "This coupon is not active");
        }
        if (now.isBefore(c.startsAt())) {
            return CouponEvaluation.rejected(CouponRejection.NOT_STARTED, "This coupon is not valid yet");
        }
        if (c.endsAt() != null && !now.isBefore(c.endsAt())) {
            return CouponEvaluation.rejected(CouponRejection.EXPIRED, "This coupon has expired");
        }
        if (!c.currency().equals(cart.currency())) {
            return CouponEvaluation.rejected(CouponRejection.CURRENCY_MISMATCH,
                    "This coupon is for " + c.currency() + " carts");
        }
        if (c.clipRequired() && !usage.clipped()) {
            return CouponEvaluation.rejected(CouponRejection.NOT_CLIPPED, "Clip this coupon before checkout");
        }
        if (c.totalLimit() > 0 && usage.totalRedeemed() >= c.totalLimit()) {
            return CouponEvaluation.rejected(CouponRejection.TOTAL_LIMIT_REACHED,
                    "This coupon has been fully redeemed");
        }
        if (c.perCustomerLimit() > 0 && usage.customerRedeemed() >= c.perCustomerLimit()) {
            return CouponEvaluation.rejected(CouponRejection.CUSTOMER_LIMIT_REACHED,
                    "You have already used this coupon");
        }
        if (c.scope() == CouponScope.FIRST_ORDER && !cart.firstOrder()) {
            return CouponEvaluation.rejected(CouponRejection.FIRST_ORDER_ONLY, "Valid on your first order only");
        }
        List<CouponLine> eligible = cart.lines().stream().filter(l -> appliesTo(c, l)).toList();
        if (eligible.isEmpty()) {
            return CouponEvaluation.rejected(CouponRejection.NO_ELIGIBLE_ITEMS,
                    "No items in your cart qualify for this coupon");
        }
        long subtotal = eligible.stream().mapToLong(CouponLine::totalMinor).sum();
        if (subtotal < c.minSpendMinor()) {
            return CouponEvaluation.rejected(CouponRejection.BELOW_MINIMUM_SPEND,
                    "Spend " + DefaultBuyBoxPolicy.money(c.minSpendMinor() - subtotal, c.currency())
                            + " more on qualifying items to use this coupon");
        }
        return switch (c.type()) {
            case PERCENT -> {
                long discount = Math.multiplyExact(subtotal, c.value()) / 100;
                if (c.maxDiscountMinor() != null) {
                    discount = Math.min(discount, c.maxDiscountMinor());
                }
                yield applied(subtotal, discount, 0, eligible, "You save " + c.value() + "%");
            }
            case FIXED -> {
                long discount = Math.min(c.value(), subtotal);
                yield applied(subtotal, discount, 0, eligible, "You save "
                        + DefaultBuyBoxPolicy.money(discount, c.currency()));
            }
            case FREE_SHIPPING -> {
                if (cart.shippingMinor() <= 0) {
                    yield CouponEvaluation.rejected(CouponRejection.NO_SHIPPING_TO_DISCOUNT,
                            "Shipping is already free");
                }
                yield applied(subtotal, 0, cart.shippingMinor(), List.of(), "Free shipping");
            }
        };
    }

    @Override
    public boolean appliesTo(Coupon c, CouponLine l) {
        if (c.funding() == CouponFunding.SELLER && !c.fundedByOrg().equals(l.sellerOrgId())) {
            return false;
        }
        return switch (c.scope()) {
            case SITEWIDE, FIRST_ORDER -> true;
            case CATEGORY -> c.scopeRef().equals(l.categoryId());
            case SELLER -> c.scopeRef().equals(l.sellerOrgId());
            case PRODUCT -> c.scopeRef().equals(l.atin());
            case FULFILLED_BY_AFROTRANSACT -> l.fulfilment() == Fulfilment.AFROTRANSACT;
        };
    }

    private static CouponEvaluation applied(long subtotal, long discount, long shippingDiscount,
                                            List<CouponLine> eligible, String message) {
        return new CouponEvaluation(true, null, message, subtotal, discount, shippingDiscount,
                allocate(discount, subtotal, eligible));
    }

    /** Pro-rata allocation by line total; leftover minor units go to the largest lines first (ties: cart order). */
    static List<LineDiscount> allocate(long discount, long subtotal, List<CouponLine> lines) {
        if (discount <= 0 || lines.isEmpty() || subtotal <= 0) {
            return List.of();
        }
        long[] shares = new long[lines.size()];
        long allocated = 0;
        for (int i = 0; i < lines.size(); i++) {
            shares[i] = Math.multiplyExact(discount, lines.get(i).totalMinor()) / subtotal;
            allocated += shares[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(lines.get(b).totalMinor(), lines.get(a).totalMinor()));
        long remainder = discount - allocated;
        while (remainder > 0) {
            boolean progressed = false;
            for (int i : order) {
                if (remainder == 0) {
                    break;
                }
                if (shares[i] < lines.get(i).totalMinor()) {
                    shares[i]++;
                    remainder--;
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
        List<LineDiscount> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            out.add(new LineDiscount(lines.get(i).lineRef(), lines.get(i).sellerOrgId(), shares[i]));
        }
        return out;
    }
}
