package com.afrotransact.offers.config;

import com.afrotransact.offers.application.OffersSettings;
import com.afrotransact.offers.domain.BuyBoxPolicy;
import com.afrotransact.offers.domain.BuyBoxWeights;
import com.afrotransact.offers.domain.CouponPolicy;
import com.afrotransact.offers.domain.DefaultBuyBoxPolicy;
import com.afrotransact.offers.domain.DefaultCouponPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and service settings. */
@Configuration(proxyBeanMethods = false)
class OffersConfiguration {

    @Bean
    OffersSettings offersSettings(@Value("${at.offers.retail-org-id}") String retailOrgId,
                                  @Value("${at.offers.default-handling-days:2}") int defaultHandlingDays,
                                  @Value("${at.offers.afrotransact-transit-days:2}") int afrotransactTransitDays,
                                  @Value("${at.offers.seller-transit-days:4}") int sellerTransitDays,
                                  @Value("${at.offers.low-stock-threshold:5}") int lowStockThreshold,
                                  @Value("${at.offers.afrotransact-delivery-transit-days:1}") int deliveryTransitDays) {
        return new OffersSettings(retailOrgId, defaultHandlingDays, afrotransactTransitDays, sellerTransitDays,
                lowStockThreshold, deliveryTransitDays);
    }

    @Bean
    BuyBoxWeights buyBoxWeights(@Value("${at.offers.buy-box.price-points:600}") double price,
                                @Value("${at.offers.buy-box.fulfilment-points:100}") double fulfilment,
                                @Value("${at.offers.buy-box.delivery-points:100}") double delivery,
                                @Value("${at.offers.buy-box.performance-points:150}") double performance,
                                @Value("${at.offers.buy-box.stock-points:50}") double stock,
                                @Value("${at.offers.buy-box.full-stock-units:10}") int fullStockUnits,
                                @Value("${at.offers.buy-box.max-defect-rate:0.02}") double maxDefectRate,
                                @Value("${at.offers.buy-box.min-on-time-rate:0.80}") double minOnTimeRate,
                                @Value("${at.offers.buy-box.new-seller-rating:4.0}") double newSellerRating) {
        return new BuyBoxWeights(price, fulfilment, delivery, performance, stock, fullStockUnits, maxDefectRate,
                minOnTimeRate, newSellerRating);
    }

    @Bean
    BuyBoxPolicy buyBoxPolicy(BuyBoxWeights weights) {
        return new DefaultBuyBoxPolicy(weights);
    }

    @Bean
    CouponPolicy couponPolicy() {
        return new DefaultCouponPolicy();
    }
}
