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
 */
public record OrderLine(String lineId, String sellerOrg, String category, Fulfilment fulfilment, int quantity,
                        Money items, Money shipping, Money tax, String taxJurisdiction) {

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
    }

    /** Items + shipping + tax: what the shopper pays for the line. */
    public Money total() {
        return items.plus(shipping).plus(tax);
    }

    public String currency() {
        return items.currency();
    }
}
