package com.afrotransact.orders.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.orders.*} settings.
 *
 * @param retailOrgId             AfroTransact Retail organisation (1P)
 * @param paymentTimeout          how long checkout waits for the authorisation (= reservation TTL, 15 min)
 * @param checkoutResponseTimeout how long POST /v1/checkout waits for the saga before answering 202
 * @param zone                    business time zone for promised dates
 * @param perishableTypes         catalog product types treated as perishable
 * @param commissionCategories    catalog product type → payments commission category
 * @param shipping                fees and fallback delivery estimates
 * @param tax                     tax provider (tax-returns quote) and the interim per-state fallback rates
 * @param jobs                    follow-up job settings
 * @param returns                 return windows shown on order views (tax-returns decides eligibility)
 * @param shipBy                  handling time before a shipment must be handed over
 */
@ConfigurationProperties(prefix = "at.orders")
public record OrdersProperties(String retailOrgId, Duration paymentTimeout,
                               Duration checkoutResponseTimeout, String zone, List<String> perishableTypes,
                               Map<String, String> commissionCategories, Shipping shipping, Tax tax, Jobs jobs,
                               Returns returns, ShipBy shipBy) {

    public OrdersProperties {
        retailOrgId = retailOrgId == null ? "org_afrotransact_retail" : retailOrgId;
        paymentTimeout = paymentTimeout == null ? Duration.ofMinutes(15) : paymentTimeout;
        checkoutResponseTimeout = checkoutResponseTimeout == null ? Duration.ofSeconds(20) : checkoutResponseTimeout;
        zone = zone == null ? "America/Chicago" : zone;
        perishableTypes = perishableTypes == null ? List.of("perishable") : List.copyOf(perishableTypes);
        commissionCategories = commissionCategories == null ? Map.of() : Map.copyOf(commissionCategories);
        shipping = shipping == null ? new Shipping(null, null, null, null, null, null, null, null, null) : shipping;
        tax = tax == null ? new Tax(null, null, null, null) : tax;
        jobs = jobs == null ? new Jobs(null, null, null) : jobs;
        returns = returns == null ? new Returns(null, null, null) : returns;
        shipBy = shipBy == null ? new ShipBy(null, null) : shipBy;
    }

    /** Shipping fees (minor units) and fallback estimates (business days). */
    public record Shipping(Long carrierStandardMinor, Long carrierExpressMinor, Long freeCarrierAboveMinor,
                           Long deliveryMinor, Long freeDeliveryAboveMinor, Integer standardDays, Integer expressDays,
                           Integer deliveryDays, Integer pickupDays) {

        public Shipping {
            carrierStandardMinor = carrierStandardMinor == null ? 599L : carrierStandardMinor;
            carrierExpressMinor = carrierExpressMinor == null ? 1499L : carrierExpressMinor;
            freeCarrierAboveMinor = freeCarrierAboveMinor == null ? 3500L : freeCarrierAboveMinor;
            deliveryMinor = deliveryMinor == null ? 499L : deliveryMinor;
            freeDeliveryAboveMinor = freeDeliveryAboveMinor == null ? 5000L : freeDeliveryAboveMinor;
            standardDays = standardDays == null ? 5 : standardDays;
            expressDays = expressDays == null ? 2 : expressDays;
            deliveryDays = deliveryDays == null ? 1 : deliveryDays;
            pickupDays = pickupDays == null ? 1 : pickupDays;
        }
    }

    /**
     * Checkout tax.
     *
     * @param rates           fallback: combined sales-tax rate per US state in basis points (825 = 8.25 %)
     * @param defaultBps      fallback rate for states not listed
     * @param provider        TAX_RETURNS (quote from tax-returns-service, default) or TABLE (rates only)
     * @param fallbackToTable tax with the rates (and log a warning) when tax-returns is unavailable (default true)
     */
    public record Tax(Map<String, Integer> rates, Integer defaultBps, String provider, Boolean fallbackToTable) {

        public Tax {
            rates = rates == null ? Map.of() : Map.copyOf(rates);
            defaultBps = defaultBps == null ? 0 : defaultBps;
            provider = provider == null || provider.isBlank() ? "TAX_RETURNS" : provider.trim().toUpperCase(Locale.ROOT);
            fallbackToTable = fallbackToTable == null ? Boolean.TRUE : fallbackToTable;
        }
    }

    /**
     * Return windows for the order views' "Return or replace items" links.
     *
     * @param windowDays                default window after delivery
     * @param windowDaysByProductType   catalog product type → window
     * @param nonReturnableProductTypes product types that can only be reported as a problem
     */
    public record Returns(Integer windowDays, Map<String, Integer> windowDaysByProductType,
                          List<String> nonReturnableProductTypes) {

        public Returns {
            windowDays = windowDays == null ? 30 : windowDays;
            windowDaysByProductType = windowDaysByProductType == null ? Map.of() : Map.copyOf(windowDaysByProductType);
            nonReturnableProductTypes = nonReturnableProductTypes == null ? List.of("perishable")
                    : List.copyOf(nonReturnableProductTypes);
        }
    }

    /** Handling time in business days after the order day before the shipment must be handed over. */
    public record ShipBy(Integer afrotransactDays, Integer sellerDays) {

        public ShipBy {
            afrotransactDays = afrotransactDays == null ? 1 : afrotransactDays;
            sellerDays = sellerDays == null ? 2 : sellerDays;
        }
    }

    /** Follow-up job switches. */
    public record Jobs(Boolean enabled, Integer followUpBatch, Integer followUpMaxAttempts) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            followUpBatch = followUpBatch == null ? 50 : followUpBatch;
            followUpMaxAttempts = followUpMaxAttempts == null ? 12 : followUpMaxAttempts;
        }
    }
}
