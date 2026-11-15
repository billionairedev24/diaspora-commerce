package com.afrotransact.offers.config;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessEdge;
import com.afrotransact.seller.api.SellerLookupApi;
import com.afrotransact.taxreturns.api.ListingDecisionApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The in-process edges of offers (mode {@code at.offers.mode=in-process}, the default in core). The adapters themselves are
 * {@code adapter.out.module} components that replace the HTTP adapter of the same port (switched off in this mode, kept for the
 * standalone build). Here each callee is checked at start: a module that is not running in this JVM stops the start naming
 * both modules.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${at.offers.mode:http}' == 'in-process'")
class InProcessLinksConfiguration {

    private static final String MODULE = "offers";
    private static final String MODE = "at.offers.mode";

    @Bean
    InProcessEdge offersToCatalog(ListableBeanFactory beans) {
        return edge(beans, CatalogLookupApi.class, "catalog");
    }

    @Bean
    InProcessEdge offersToSeller(ListableBeanFactory beans) {
        return edge(beans, SellerLookupApi.class, "seller");
    }

    @Bean
    InProcessEdge offersToTaxReturns(ListableBeanFactory beans) {
        return edge(beans, ListingDecisionApi.class, "tax-returns");
    }

    private static InProcessEdge edge(ListableBeanFactory beans, Class<?> api, String callee) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(api, true, false).length > 0, MODULE, callee, MODE);
        return new InProcessEdge(MODULE, callee);
    }
}
