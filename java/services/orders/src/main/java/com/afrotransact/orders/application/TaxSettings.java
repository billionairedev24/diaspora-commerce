package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.ShippingAllocation;

/**
 * How checkout taxes orders ({@code at.orders.tax.*}). Tax always comes from tax-returns' stored decision per line;
 * there is no fallback rate table and no default rate.
 *
 * @param shippingAllocation how a package's delivery charge is spread over its lines (TX-05 to TX-07). The mixed-basket
 *                           method is an OPEN LEGAL DECISION (open decision 5) the tax adviser must approve before
 *                           production; {@code FIRST_LINE} is the rollback to the previous behaviour.
 */
public record TaxSettings(ShippingAllocation shippingAllocation) {

    public TaxSettings {
        shippingAllocation = shippingAllocation == null ? ShippingAllocation.PROPORTIONAL : shippingAllocation;
    }
}
