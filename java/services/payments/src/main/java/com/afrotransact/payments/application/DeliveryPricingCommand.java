package com.afrotransact.payments.application;

import java.math.BigDecimal;
import java.util.List;

import com.afrotransact.payments.domain.DeliveryBand;
import com.afrotransact.payments.domain.DeliveryMode;
import com.afrotransact.payments.domain.DeliveryPricing;
import com.afrotransact.payments.domain.DeliveryTariff;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.SellerDeliveryOverride;

/**
 * The "Delivery pricing" section of a new fee-schedule version (S255), in plain numbers.
 *
 * @param enabled false = the version offers no AfroTransact Delivery from seller stores
 */
public record DeliveryPricingCommand(boolean enabled, BigDecimal maxRadiusMiles, Tariff customerCharge,
                                     Tariff sellerFee, List<Override> sellerOverrides) {

    public DeliveryPricingCommand {
        sellerOverrides = sellerOverrides == null ? List.of() : List.copyOf(sellerOverrides);
    }

    /** One tariff (FLAT or DISTANCE). */
    public record Tariff(DeliveryMode mode, long flatPerPackageMinor, long baseMinor, long perMileMinor,
                         List<Band> bands, long minMinor, Long maxMinor) {

        public Tariff {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }

        DeliveryTariff toTariff(String currency) {
            return new DeliveryTariff(mode == null ? DeliveryMode.FLAT : mode, Money.of(flatPerPackageMinor, currency),
                    Money.of(baseMinor, currency), perMileMinor, bands.stream()
                    .map(b -> new DeliveryBand(b.fromMiles(), b.toMiles(), Money.of(b.feeMinor(), currency)))
                    .toList(), Money.of(minMinor, currency), maxMinor == null ? null : Money.of(maxMinor, currency));
        }
    }

    /** A distance band: [fromMiles, toMiles) costs feeMinor. */
    public record Band(BigDecimal fromMiles, BigDecimal toMiles, long feeMinor) {
    }

    /** A negotiated seller fee for one organisation. */
    public record Override(String orgId, Tariff sellerFee) {
    }

    /** The domain section, or null when disabled. Missing tariffs are free (zero). */
    DeliveryPricing toPricing(String currency) {
        if (!enabled) {
            return null;
        }
        return new DeliveryPricing(
                customerCharge == null ? DeliveryTariff.free(currency) : customerCharge.toTariff(currency),
                sellerFee == null ? DeliveryTariff.free(currency) : sellerFee.toTariff(currency),
                maxRadiusMiles, sellerOverrides.stream()
                .map(o -> new SellerDeliveryOverride(o.orgId(), o.sellerFee() == null ? DeliveryTariff.free(currency)
                        : o.sellerFee().toTariff(currency))).toList());
    }
}
