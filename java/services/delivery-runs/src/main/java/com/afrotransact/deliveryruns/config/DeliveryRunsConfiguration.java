package com.afrotransact.deliveryruns.config;

import java.time.Clock;

import com.afrotransact.deliveryruns.adapter.out.geo.HttpGeoClient;
import com.afrotransact.deliveryruns.application.DeliveryRunsSettings;
import com.afrotransact.deliveryruns.domain.CutoffDecisionPolicy;
import com.afrotransact.deliveryruns.domain.DefaultCutoffDecisionPolicy;
import com.afrotransact.deliveryruns.domain.DefaultRunEconomicsCalculator;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RunEconomicsCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                e.perStopMinutes(), e.pickupPointStopMinutes(), e.handoverMinutesPerOrder(), e.roundTrip());
    }

    @Bean
    RunEconomicsCalculator runEconomicsCalculator(EconomicsSettings settings) {
        return new DefaultRunEconomicsCalculator(settings);
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
}
