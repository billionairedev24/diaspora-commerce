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
 * @param cancellationWindow      how long after placement a customer may cancel a shipment (default 30 min)
 * @param zone                    business time zone for promised dates
 * @param perishableTypes         catalog product types treated as perishable
 * @param commissionCategories    catalog product type → payments commission category
 * @param shipping                fees and fallback delivery estimates
 * @param tax                     checkout tax: delivery charge allocation (rates come from tax-returns)
 * @param jobs                    follow-up job settings
 * @param returns                 return windows shown on order views (tax-returns decides eligibility)
 * @param shipBy                  handling time before a shipment must be handed over
 */
@ConfigurationProperties(prefix = "at.orders")
public record OrdersProperties(String retailOrgId, Duration paymentTimeout,
                               Duration checkoutResponseTimeout, Duration cancellationWindow, String zone,
                               List<String> perishableTypes, Map<String, String> commissionCategories,
                               Shipping shipping, Tax tax, Jobs jobs, Returns returns, ShipBy shipBy) {

    public OrdersProperties {
        retailOrgId = retailOrgId == null ? "org_afrotransact_retail" : retailOrgId;
        paymentTimeout = paymentTimeout == null ? Duration.ofMinutes(15) : paymentTimeout;
        checkoutResponseTimeout = checkoutResponseTimeout == null ? Duration.ofSeconds(20) : checkoutResponseTimeout;
        cancellationWindow = cancellationWindow == null ? Duration.ofMinutes(30) : cancellationWindow;
        zone = zone == null ? "America/Chicago" : zone;
        perishableTypes = perishableTypes == null ? List.of("perishable") : List.copyOf(perishableTypes);
        commissionCategories = commissionCategories == null ? Map.of() : Map.copyOf(commissionCategories);
        shipping = shipping == null ? new Shipping(null, null, null, null, null, null, null, null, null) : shipping;
        tax = tax == null ? new Tax(null) : tax;
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
     * Checkout tax. Tax always comes from tax-returns' stored decision per line (there is no rate table and no default
     * rate; when tax-returns is down the checkout step is retried, and a line without a definite decision is blocked).
     *
     * @param shippingAllocation PROPORTIONAL (default) spreads a package's delivery charge over its lines by net item
     *                           value (TX-05 to TX-07); FIRST_LINE is the previous behaviour, kept as the rollback.
     *                           The mixed-basket method is an OPEN LEGAL DECISION (open decision 5): the tax adviser
     *                           must approve it before production.
     */
    public record Tax(String shippingAllocation) {

        public Tax {
            shippingAllocation = shippingAllocation == null || shippingAllocation.isBlank() ? "PROPORTIONAL"
                    : shippingAllocation.trim().toUpperCase(Locale.ROOT);
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
