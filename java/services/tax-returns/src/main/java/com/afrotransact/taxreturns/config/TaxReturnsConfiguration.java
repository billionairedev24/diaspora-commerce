package com.afrotransact.taxreturns.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.afrotransact.taxreturns.application.ReturnsSettings;
import com.afrotransact.taxreturns.application.TaxSettings;
import com.afrotransact.taxreturns.domain.returns.DefaultRefundCalculator;
import com.afrotransact.taxreturns.domain.returns.DefaultReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.RefundCalculator;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicySettings;
import com.afrotransact.taxreturns.domain.tax.DefaultTaxRules;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxRules;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaxReturnsProperties.class)
class TaxReturnsConfiguration {

    @Bean
    TaxRules taxRules(TaxReturnsProperties props) {
        if (props.tax().provider() != TaxReturnsProperties.TaxProvider.RULES) {
            throw new IllegalStateException("at.taxreturns.tax.provider=" + props.tax().provider()
                    + " needs an external TaxCalculator adapter, which is not installed; use RULES");
        }
        return new DefaultTaxRules();
    }

    @Bean
    TaxSettings taxSettings(TaxReturnsProperties props) {
        Map<String, TaxCategory> map = new HashMap<>();
        props.tax().categoryByProductType().forEach((type, category) -> map.put(type, TaxCategory.parse(category)));
        return new TaxSettings(map);
    }

    @Bean
    ReturnPolicy returnPolicy(TaxReturnsProperties props) {
        TaxReturnsProperties.Returns r = props.returns();
        return new DefaultReturnPolicy(new ReturnPolicySettings(r.defaultWindowDays(), r.windowDaysByProductType(),
                new HashSet<>(r.nonReturnableProductTypes()), r.lowValueThresholdMinor(), r.firstScanRefundMaxMinor(),
                r.sellerApprovalSla(), r.dropOffDays(), r.maxRestockingFeeBps(), r.maxEvidence()));
    }

    @Bean
    RefundCalculator refundCalculator() {
        return new DefaultRefundCalculator();
    }

    @Bean
    ReturnsSettings returnsSettings(TaxReturnsProperties props) {
        TaxReturnsProperties.Address a = props.returns().returnCentre();
        return new ReturnsSettings(new ReturnAddress(a.name(), a.line1(), a.line2(), a.city(), a.state(),
                a.postalCode(), a.country(), a.phone()), a.node(), props.returns().label().weightLb(),
                Duration.ofMinutes(1), props.returns().pickupReadyDays(), a.inventoryNode());
    }
}
