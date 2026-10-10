package com.afrotransact.payments.domain;

import java.util.Objects;

/**
 * One order line as charged to the shopper, in the payment's currency.
 *
 * @param sellerOrg       seller / provider organisation (AfroTransact Retail for 1P)
 * @param category        commission category (e.g. Groceries, Services)
 * @param items           item amount after discounts (unit price × qty)
 * @param shipping        shipping charged for this line (FBA shipping is AfroTransact's revenue)
 * @param tax             sales tax collected on the line (remitted by AfroTransact, attributed to the seller)
 * @param taxJurisdiction jurisdiction code of the tax (e.g. US-TX-48201)
 * @param shippingTax     the part of {@code tax} charged on shipping (states that tax delivery charges); 0 when
 *                        none or unknown
 * @param deliveryFee     the seller's AfroTransact Delivery pickup-delivery fee allocated to this line (S255),
 *                        quoted at checkout and snapshotted by orders; only on {@link Fulfilment#AFROTRANSACT_DELIVERY}
 *                        lines, 0 otherwise
 */
public record OrderLine(String lineId, String sellerOrg, String category, Fulfilment fulfilment, int quantity,
                        Money items, Money shipping, Money tax, String taxJurisdiction, Money shippingTax,
                        Money deliveryFee) {

    /** A line without a seller delivery fee. */
    public OrderLine(String lineId, String sellerOrg, String category, Fulfilment fulfilment, int quantity,
                     Money items, Money shipping, Money tax, String taxJurisdiction, Money shippingTax) {
        this(lineId, sellerOrg, category, fulfilment, quantity, items, shipping, tax, taxJurisdiction, shippingTax,
                null);
    }

    /** A line whose tax is all on the items. */
    public OrderLine(String lineId, String sellerOrg, String category, Fulfilment fulfilment, int quantity,
                     Money items, Money shipping, Money tax, String taxJurisdiction) {
        this(lineId, sellerOrg, category, fulfilment, quantity, items, shipping, tax, taxJurisdiction, null);
    }

    public OrderLine {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(sellerOrg, "sellerOrg");
        Objects.requireNonNull(fulfilment, "fulfilment");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(shipping, "shipping");
        Objects.requireNonNull(tax, "tax");
        category = category == null || category.isBlank() ? "general" : category.trim();
        taxJurisdiction = taxJurisdiction == null || taxJurisdiction.isBlank() ? "US" : taxJurisdiction.trim();
        if (quantity < 1) {
            throw new PaymentsRuleViolation("payments.invalid-line", "Line " + lineId + " needs a quantity >= 1");
        }
        if (items.isNegative() || shipping.isNegative() || tax.isNegative()) {
            throw new PaymentsRuleViolation("payments.invalid-line", "Line " + lineId + " has a negative amount");
        }
        items.requireSameCurrency(shipping);
        items.requireSameCurrency(tax);
        shippingTax = shippingTax == null ? Money.zero(items.currency()) : shippingTax;
        items.requireSameCurrency(shippingTax);
        if (shippingTax.isNegative() || shippingTax.greaterThan(tax)) {
            throw new PaymentsRuleViolation("payments.invalid-line", "Line " + lineId
                    + " has a shipping tax outside 0 … tax");
        }
        deliveryFee = deliveryFee == null ? Money.zero(items.currency()) : deliveryFee;
        items.requireSameCurrency(deliveryFee);
        if (deliveryFee.isNegative()) {
            throw new PaymentsRuleViolation("payments.invalid-line", "Line " + lineId
                    + " has a negative delivery fee");
        }
        if (deliveryFee.isPositive() && fulfilment != Fulfilment.AFROTRANSACT_DELIVERY) {
            throw new PaymentsRuleViolation("payments.invalid-line", "Line " + lineId
                    + " has a delivery fee but is not AFROTRANSACT_DELIVERY");
        }
    }

    /** Tax on the items: tax − shipping tax. */
    public Money itemsTax() {
        return tax.minus(shippingTax);
    }

    /** Items + shipping + tax: what the shopper pays for the line. */
    public Money total() {
        return items.plus(shipping).plus(tax);
    }

    public String currency() {
        return items.currency();
    }
}
