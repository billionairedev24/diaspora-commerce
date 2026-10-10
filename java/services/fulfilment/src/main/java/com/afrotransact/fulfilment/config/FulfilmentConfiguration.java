package com.afrotransact.fulfilment.config;

import java.time.Duration;

import com.afrotransact.fulfilment.domain.DefaultInspectionPolicy;
import com.afrotransact.fulfilment.domain.DefaultPickupPolicy;
import com.afrotransact.fulfilment.domain.DefaultProofOfDeliveryPolicy;
import com.afrotransact.fulfilment.domain.DefaultReattemptPolicy;
import com.afrotransact.fulfilment.domain.DefaultSequencePolicy;
import com.afrotransact.fulfilment.domain.InspectionPolicy;
import com.afrotransact.fulfilment.domain.PickupPolicy;
import com.afrotransact.fulfilment.domain.ProofOfDeliveryPolicy;
import com.afrotransact.fulfilment.domain.ReattemptPolicy;
import com.afrotransact.fulfilment.domain.RoutePlanner;
import com.afrotransact.fulfilment.domain.SequencePolicy;
import com.afrotransact.fulfilment.domain.ZoneRoutePlanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of the framework-free domain policies (thresholds from {@code at.fulfilment.*}). */
@Configuration(proxyBeanMethods = false)
class FulfilmentConfiguration {

    @Bean
    InspectionPolicy inspectionPolicy() {
        return new DefaultInspectionPolicy();
    }

    @Bean
    ProofOfDeliveryPolicy proofOfDeliveryPolicy(
            @Value("${at.fulfilment.pod.max-distance-meters:150}") double maxDistanceMeters) {
        return new DefaultProofOfDeliveryPolicy(maxDistanceMeters);
    }

    @Bean
    ReattemptPolicy reattemptPolicy(@Value("${at.fulfilment.reattempt.wait:5m}") Duration wait,
                                    @Value("${at.fulfilment.reattempt.max-reattempts:2}") int maxReattempts) {
        return new DefaultReattemptPolicy(wait, maxReattempts);
    }

    @Bean
    PickupPolicy pickupPolicy() {
        return new DefaultPickupPolicy();
    }

    @Bean
    SequencePolicy sequencePolicy() {
        return new DefaultSequencePolicy();
    }

    @Bean
    RoutePlanner routePlanner() {
        return new ZoneRoutePlanner();
    }
}
