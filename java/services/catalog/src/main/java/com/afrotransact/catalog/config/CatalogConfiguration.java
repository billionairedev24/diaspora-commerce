package com.afrotransact.catalog.config;

import com.afrotransact.catalog.application.LabelSettings;
import com.afrotransact.catalog.application.OwnerResolver;
import com.afrotransact.catalog.application.ProductDataSettings;
import com.afrotransact.catalog.application.RetailAwareOwnerResolver;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import com.afrotransact.catalog.domain.barcode.BarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultBarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultLabelPolicy;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.prefill.CategoryHintPolicy;
import com.afrotransact.catalog.domain.prefill.DefaultCategoryHintPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and configurable collaborators. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductDataProperties.class)
class CatalogConfiguration {

    @Bean
    ProductDraftPolicy productDraftPolicy() {
        return new DefaultProductDraftPolicy();
    }

    @Bean
    OwnerResolver ownerResolver(@Value("${at.catalog.retail-org-id}") String retailOrgId) {
        return new RetailAwareOwnerResolver(retailOrgId);
    }

    @Bean
    BarcodeEncoder barcodeEncoder() {
        return new DefaultBarcodeEncoder();
    }

    @Bean
    LabelPolicy labelPolicy(BarcodeEncoder encoder) {
        return new DefaultLabelPolicy(encoder);
    }

    @Bean
    LabelSettings labelSettings(@Value("${at.catalog.retail-org-id}") String retailOrgId) {
        return new LabelSettings(retailOrgId);
    }

    @Bean
    CategoryHintPolicy categoryHintPolicy() {
        return new DefaultCategoryHintPolicy();
    }

    @Bean
    ProductDataSettings productDataSettings(ProductDataProperties props) {
        return new ProductDataSettings(props.order(), props.cacheTtl(), props.missTtl());
    }
}
