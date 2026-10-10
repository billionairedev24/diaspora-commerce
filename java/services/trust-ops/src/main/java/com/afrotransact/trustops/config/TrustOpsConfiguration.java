package com.afrotransact.trustops.config;

import java.util.LinkedHashSet;

import com.afrotransact.trustops.application.ClaimSettings;
import com.afrotransact.trustops.application.TrustOpsSettings;
import com.afrotransact.trustops.domain.AuditClassifier;
import com.afrotransact.trustops.domain.AuditHashChain;
import com.afrotransact.trustops.domain.DefaultAuditClassifier;
import com.afrotransact.trustops.domain.DefaultAuditHashChain;
import com.afrotransact.trustops.domain.DefaultSellerHealthPolicy;
import com.afrotransact.trustops.domain.DefaultSlaPolicy;
import com.afrotransact.trustops.domain.SellerHealthPolicy;
import com.afrotransact.trustops.domain.SlaPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and business settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrustOpsProperties.class)
class TrustOpsConfiguration {

    @Bean
    SlaPolicy slaPolicy(TrustOpsProperties props) {
        return new DefaultSlaPolicy(props.sla().targets());
    }

    @Bean
    AuditHashChain auditHashChain() {
        return new DefaultAuditHashChain();
    }

    @Bean
    AuditClassifier auditClassifier() {
        return new DefaultAuditClassifier();
    }

    @Bean
    SellerHealthPolicy sellerHealthPolicy(TrustOpsProperties props) {
        TrustOpsProperties.SellerHealth h = props.sellerHealth();
        return new DefaultSellerHealthPolicy(h.lateShipmentThreshold(), h.cancellationThreshold(),
                h.defectThreshold());
    }

    @Bean
    ClaimSettings claimSettings(TrustOpsProperties props) {
        return new ClaimSettings(props.claims().verifyOwnership());
    }

    @Bean
    TrustOpsSettings trustOpsSettings(TrustOpsProperties props) {
        return new TrustOpsSettings(new LinkedHashSet<>(props.audit().eventTypes()), props.audit().streamEnabled(),
                props.audit().exportMaxRows(), props.sellerHealth().window(), props.sellerHealth().pushToOffers(),
                props.sla().sellerResponseReminder());
    }
}
