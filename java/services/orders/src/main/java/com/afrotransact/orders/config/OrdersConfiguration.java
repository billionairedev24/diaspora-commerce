package com.afrotransact.orders.config;

import java.time.Duration;
import java.util.Set;

import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.application.PreorderSettings;
import com.afrotransact.orders.application.TaxSettings;
import com.afrotransact.orders.domain.CourierProofPolicy;
import com.afrotransact.orders.domain.DefaultCourierProofPolicy;
import com.afrotransact.orders.domain.DefaultHandoverPolicy;
import com.afrotransact.orders.domain.DefaultOrderNumberGenerator;
import com.afrotransact.orders.domain.DefaultReturnWindowPolicy;
import com.afrotransact.orders.domain.DefaultShipByPolicy;
import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DefaultShippingPolicy;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.OrderNumberGenerator;
import com.afrotransact.orders.domain.ReturnWindowPolicy;
import com.afrotransact.orders.domain.ShipByPolicy;
import com.afrotransact.orders.domain.ShipmentTransitionPolicy;
import com.afrotransact.orders.domain.ShippingPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit wiring of the framework-free domain policies and settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrdersProperties.class)
class OrdersConfiguration {

    @Bean
    ShipmentTransitionPolicy shipmentTransitionPolicy() {
        return new DefaultShipmentTransitionPolicy();
    }

    @Bean
    HandoverPolicy handoverPolicy() {
        return new DefaultHandoverPolicy();
    }

    @Bean
    CourierProofPolicy courierProofPolicy(HandoverPolicy handover) {
        return new DefaultCourierProofPolicy(handover);
    }

    @Bean
    OrderNumberGenerator orderNumberGenerator() {
        return new DefaultOrderNumberGenerator();
    }

    @Bean
    ShippingPolicy shippingPolicy(OrdersProperties props) {
        OrdersProperties.Shipping s = props.shipping();
        return new DefaultShippingPolicy(new DefaultShippingPolicy.Rates(s.carrierStandardMinor(),
                s.carrierExpressMinor(), s.freeCarrierAboveMinor(), s.deliveryMinor(), s.freeDeliveryAboveMinor(),
                s.standardDays(), s.expressDays(), s.deliveryDays(), s.pickupDays()));
    }

    @Bean
    OrdersSettings ordersSettings(OrdersProperties props) {
        return new OrdersSettings(props.retailOrgId(), props.paymentTimeout(),
                props.checkoutResponseTimeout(), props.cancellationWindow(),
                Set.copyOf(props.perishableTypes()), props.commissionCategories(),
                props.jobs().followUpBatch(), props.jobs().followUpMaxAttempts(), props.zone());
    }

    @Bean
    PreorderSettings preorderSettings(@Value("${at.orders.preorders.enabled:true}") boolean enabled,
                                      @Value("${at.orders.preorders.runs-enabled:true}") boolean runsEnabled,
                                      @Value("${at.orders.preorders.job-batch:100}") int jobBatch,
                                      @Value("${at.orders.preorders.link-expiry-hours:24}") int linkExpiryHours,
                                      @Value("${at.orders.pay-links.base-url:http://localhost:3000}") String payPage,
                                      @Value("${at.orders.preorders.late-add-duplicate-window:10m}")
                                      Duration duplicateWindow) {
        return new PreorderSettings(enabled, runsEnabled, jobBatch, linkExpiryHours, payPage, duplicateWindow);
    }

    @Bean
    TaxSettings taxSettings(OrdersProperties props) {
        return new TaxSettings(props.tax().provider(), props.tax().fallbackToTable());
    }

    @Bean
    ShipByPolicy shipByPolicy(OrdersProperties props) {
        return new DefaultShipByPolicy(props.shipBy().afrotransactDays(), props.shipBy().sellerDays());
    }

    @Bean
    ReturnWindowPolicy returnWindowPolicy(OrdersProperties props) {
        OrdersProperties.Returns r = props.returns();
        return new DefaultReturnWindowPolicy(r.windowDays(), r.windowDaysByProductType(),
                Set.copyOf(r.nonReturnableProductTypes()));
    }

    @Bean
    TransactionOperations ordersTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
