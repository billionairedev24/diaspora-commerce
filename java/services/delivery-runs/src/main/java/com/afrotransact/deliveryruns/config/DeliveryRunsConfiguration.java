package com.afrotransact.deliveryruns.config;

import java.time.Clock;

import com.afrotransact.deliveryruns.adapter.out.geo.HttpGeoClient;
import com.afrotransact.deliveryruns.adapter.out.http.HttpCatalogClient;
import com.afrotransact.deliveryruns.adapter.out.http.HttpMediaClient;
import com.afrotransact.deliveryruns.adapter.out.http.HttpOrdersClient;
import com.afrotransact.deliveryruns.adapter.out.http.HttpProductSearch;
import com.afrotransact.deliveryruns.adapter.out.places.GeoPlaceDirectory;
import com.afrotransact.deliveryruns.application.DeliveryRunsSettings;
import com.afrotransact.deliveryruns.application.EconomicsBook;
import com.afrotransact.deliveryruns.application.PreorderOptions;
import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.CutoffDecisionPolicy;
import com.afrotransact.deliveryruns.domain.DefaultCutoffDecisionPolicy;
import com.afrotransact.deliveryruns.domain.DefaultRunEconomicsCalculator;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RunEconomicsCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wiring of framework-free domain policies, settings and the geo-service client. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryRunsProperties.class)
class DeliveryRunsConfiguration {

    @Bean
    DeliveryRunsSettings deliveryRunsSettings(DeliveryRunsProperties p) {
        return new DeliveryRunsSettings(p.publicWebUrl(), p.locationPath(), p.geo().zoneType(),
                new GeoPoint(p.origin().lat(), p.origin().lng()), p.reminderLead(), p.confirmationTtl(),
                p.nextRunSearchLimit());
    }

    @Bean
    EconomicsSettings economicsSettings(DeliveryRunsProperties p) {
        DeliveryRunsProperties.Economics e = p.economics();
        return new EconomicsSettings(e.driverCostPerHourMinor(), e.driverCostPerMileMinor(), e.collectionMinutes(),
                e.perStopMinutes(), e.pickupPointStopMinutes(), e.handoverMinutesPerOrder(), e.roundTrip(),
                e.platformFeePerOrderMinor());
    }

    @Bean
    RunEconomicsCalculator runEconomicsCalculator(EconomicsBook economics) {
        return new DefaultRunEconomicsCalculator(economics);
    }

    @Bean
    CutoffDecisionPolicy cutoffDecisionPolicy() {
        return new DefaultCutoffDecisionPolicy();
    }

    @Bean
    HttpGeoClient geoClient(DeliveryRunsProperties p, Clock clock) {
        DeliveryRunsProperties.Geo g = p.geo();
        return new HttpGeoClient(g.baseUrl(), g.timeout(), g.zoneType(), g.tokenUri(), g.clientId(), g.clientSecret(),
                clock);
    }

    @Bean
    GeoPlaceDirectory placeDirectory(DeliveryRunsProperties p) {
        return new GeoPlaceDirectory(p.geo().baseUrl(), p.geo().timeout());
    }

    @Bean
    PreorderOptions preorderOptions(DeliveryRunsProperties p) {
        DeliveryRunsProperties.Preorders o = p.preorders();
        return new PreorderOptions(o.pagePath(), o.metroCities().stream().filter(k -> !k.isBlank())
                        .map(AreaKey::parse).toList(),
                o.retentionDays(), o.seedDemoAreas(), o.supportPhone(), o.defaultMinOrders());
    }

    @Bean
    HttpMediaClient mediaClient(DeliveryRunsProperties p, Clock clock) {
        DeliveryRunsProperties.Geo g = p.geo();
        return new HttpMediaClient(p.media().baseUrl(), p.media().timeout(), g.tokenUri(), g.clientId(),
                g.clientSecret(), clock);
    }

    @Bean
    HttpOrdersClient ordersClient(DeliveryRunsProperties p, Clock clock, JsonMapper json) {
        DeliveryRunsProperties.Geo g = p.geo();
        return new HttpOrdersClient(p.orders().baseUrl(), p.orders().timeout(), g.tokenUri(), g.clientId(),
                g.clientSecret(), clock, json);
    }

    @Bean
    HttpCatalogClient catalogClient(DeliveryRunsProperties p) {
        return new HttpCatalogClient(p.catalog().baseUrl(), p.catalog().timeout());
    }

    @Bean
    HttpProductSearch productSearch(DeliveryRunsProperties p) {
        return new HttpProductSearch(p.search().baseUrl(), p.search().timeout());
    }
}
