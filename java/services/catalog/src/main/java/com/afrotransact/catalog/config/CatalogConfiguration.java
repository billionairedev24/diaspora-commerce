package com.afrotransact.catalog.config;

import com.afrotransact.catalog.application.OwnerResolver;
import com.afrotransact.catalog.application.RetailAwareOwnerResolver;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and configurable collaborators. */
@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    @Bean
    ProductDraftPolicy productDraftPolicy() {
        return new DefaultProductDraftPolicy();
    }

    @Bean
    OwnerResolver ownerResolver(@Value("${at.catalog.retail-org-id}") String retailOrgId) {
        return new RetailAwareOwnerResolver(retailOrgId);
    }
}
