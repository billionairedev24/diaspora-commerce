package com.afrotransact.content.config;

import com.afrotransact.content.application.ContentSettings;
import com.afrotransact.content.domain.careers.ApplicationPolicy;
import com.afrotransact.content.domain.careers.DefaultApplicationPolicy;
import com.afrotransact.content.domain.flag.Bucketing;
import com.afrotransact.content.domain.flag.DefaultFlagEvaluator;
import com.afrotransact.content.domain.flag.FlagEvaluator;
import com.afrotransact.content.domain.flag.Murmur3Bucketing;
import com.afrotransact.content.domain.placement.DefaultPlacementResolver;
import com.afrotransact.content.domain.placement.PlacementResolver;
import com.afrotransact.content.domain.site.DefaultSiteConfigPolicy;
import com.afrotransact.content.domain.site.SiteConfigPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentProperties.class)
class ContentConfiguration {

    @Bean
    PlacementResolver placementResolver() {
        return new DefaultPlacementResolver();
    }

    @Bean
    Bucketing flagBucketing() {
        return new Murmur3Bucketing();
    }

    @Bean
    FlagEvaluator flagEvaluator(Bucketing bucketing) {
        return new DefaultFlagEvaluator(bucketing);
    }

    @Bean
    SiteConfigPolicy siteConfigPolicy() {
        return new DefaultSiteConfigPolicy();
    }

    @Bean
    ApplicationPolicy applicationPolicy() {
        return new DefaultApplicationPolicy();
    }

    @Bean
    ContentSettings contentSettings(ContentProperties p) {
        return new ContentSettings(p.flags().cacheTtl(), p.markets().cacheTtl(), p.placements().cacheTtl(),
                p.placements().resolveMaxAge(), p.placements().bundleMaxAge(), p.placements().bundleSharedMaxAge(),
                p.site().maxAge(), p.site().legalBaseHref(), p.newsletter().tokenKey(), p.newsletter().tokenTtl(),
                p.newsletter().confirmUrl(), p.newsletter().unsubscribeUrl());
    }
}
