package com.afrotransact.payments.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * AfroTransact Delivery from a seller's store or warehouse (S255), as one section of a fee-schedule version: what the
 * customer pays for the delivery, what the seller pays for the pickup-delivery (with per-seller overrides, like
 * commission overrides) and the maximum service radius beyond which the option is unavailable. Immutable, versioned
 * with its fee schedule.
 */
public record DeliveryPricing(DeliveryTariff customerCharge, DeliveryTariff sellerFee, BigDecimal maxRadiusMiles,
                              List<SellerDeliveryOverride> sellerOverrides) {

    public DeliveryPricing {
        Objects.requireNonNull(customerCharge, "customerCharge");
        Objects.requireNonNull(sellerFee, "sellerFee");
        Objects.requireNonNull(maxRadiusMiles, "maxRadiusMiles");
        sellerOverrides = List.copyOf(sellerOverrides == null ? List.of() : sellerOverrides);
        if (maxRadiusMiles.signum() <= 0 || maxRadiusMiles.compareTo(BigDecimal.valueOf(200)) > 0) {
            throw new PaymentsRuleViolation("payments.invalid-delivery-pricing", "The service radius must be "
                    + "between 0 and 200 miles", Map.of("maxRadiusMiles", "0 < radius ≤ 200"));
        }
        String currency = customerCharge.currency();
        sellerFee.base().requireSameCurrency(Money.zero(currency));
        Set<String> orgs = new HashSet<>();
        for (SellerDeliveryOverride o : sellerOverrides) {
            o.sellerFee().base().requireSameCurrency(Money.zero(currency));
            if (!orgs.add(o.orgId())) {
                throw new PaymentsRuleViolation("payments.invalid-delivery-pricing", "One seller-fee override per "
                        + "organisation (" + o.orgId() + ")", Map.of("sellerOverrides", "Duplicate " + o.orgId()));
            }
        }
    }

    public String currency() {
        return customerCharge.currency();
    }

    /** The seller-fee tariff of {@code orgId}: its override, else the default. */
    public DeliveryTariff sellerTariff(String orgId) {
        return override(orgId).map(SellerDeliveryOverride::sellerFee).orElse(sellerFee);
    }

    private Optional<SellerDeliveryOverride> override(String orgId) {
        return orgId == null ? Optional.empty()
                : sellerOverrides.stream().filter(o -> o.orgId().equals(orgId)).findFirst();
    }

    /**
     * Prices a trip. Beyond {@code maxRadiusMiles} the option is unavailable (nothing is charged). AfroTransact Retail
     * ({@code retailOrgId}) never pays a seller fee, whatever the overrides say.
     */
    public DeliveryQuote quote(FeeSchedule schedule, String sellerOrgId, BigDecimal miles, int packages,
                               String retailOrgId) {
        Money zero = Money.zero(currency());
        boolean retail = sellerOrgId != null && sellerOrgId.equals(retailOrgId);
        String source = retail ? "RETAIL" : override(sellerOrgId).isPresent() ? "OVERRIDE" : "DEFAULT";
        if (miles.compareTo(maxRadiusMiles) > 0) {
            return new DeliveryQuote(false, DeliveryQuote.BEYOND_MAX_RADIUS, schedule.id(), schedule.version(),
                    currency(), miles, packages, maxRadiusMiles, zero, zero, source);
        }
        Money customer = customerCharge.price(miles, packages);
        Money seller = retail ? zero : sellerTariff(sellerOrgId).price(miles, packages);
        return new DeliveryQuote(true, null, schedule.id(), schedule.version(), currency(), miles, packages,
                maxRadiusMiles, customer, seller, source);
    }
}
