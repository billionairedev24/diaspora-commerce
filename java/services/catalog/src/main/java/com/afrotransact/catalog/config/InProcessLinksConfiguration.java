package com.afrotransact.catalog.config;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessEdge;
import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.seller.api.SellerLookupApi;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The in-process edges of catalog (mode {@code at.catalog.mode=in-process}, the default in core). The adapters themselves are
 * {@code adapter.out.module} components that replace the HTTP adapter of the same port (switched off in this mode, kept for the
 * standalone build). Here each callee is checked at start: a module that is not running in this JVM stops the start naming
 * both modules. (Catalog -> tax-returns is wired in {@link CatalogConfiguration}.)
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${at.catalog.mode:http}' == 'in-process'")
class InProcessLinksConfiguration {

    private static final String MODULE = "catalog";
    private static final String MODE = "at.catalog.mode";

    @Bean
    InProcessEdge catalogToOffers(ListableBeanFactory beans) {
        return edge(beans, OffersApi.class, "offers");
    }

    @Bean
    InProcessEdge catalogToTaxReturns(ListableBeanFactory beans) {
        return edge(beans, TaxClassificationApi.class, "tax-returns");
    }

    @Bean
    InProcessEdge catalogToSeller(ListableBeanFactory beans) {
        return edge(beans, SellerLookupApi.class, "seller");
    }

    private static InProcessEdge edge(ListableBeanFactory beans, Class<?> api, String callee) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(api, true, false).length > 0, MODULE, callee, MODE);
        return new InProcessEdge(MODULE, callee);
    }
}
