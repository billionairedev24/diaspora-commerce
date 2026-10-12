package com.afrotransact.identity.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.afrotransact.identity.adapter.out.seller.HttpSellerOrgSource;
import com.afrotransact.identity.adapter.out.seller.SelfIssuedServiceToken;
import com.afrotransact.identity.port.SellerOrgSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Keeping identity's copy of seller and provider organisations in step with the seller service: at-least-once event
 * consumption (a failing record is retried with back-off, not dropped after a burst of retries) and the reconcile
 * read of the seller service ({@code at.identity.seller-sync.*}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SellerSyncConfiguration.SellerSyncProperties.class)
class SellerSyncConfiguration {

    /** The seller service permission identity's reconcile read needs. */
    static final String DIRECTORY_SCOPE = "sellers:directory";

    /**
     * {@code at.identity.seller-sync.*}.
     *
     * @param enabled      run the reconcile job (on start-up after {@code initialDelay}, then every {@code interval})
     * @param sellerUrl    the seller service's base URL ({@code SELLER_URL})
     * @param interval     time between reconcile runs
     * @param initialDelay time from start-up to the first run
     */
    @ConfigurationProperties(prefix = "at.identity.seller-sync")
    record SellerSyncProperties(Boolean enabled, String sellerUrl, Duration interval, Duration initialDelay) {

        SellerSyncProperties {
            enabled = enabled == null || enabled;
            sellerUrl = sellerUrl == null || sellerUrl.isBlank() ? "http://localhost:8106" : sellerUrl.trim();
            interval = interval == null ? Duration.ofMinutes(5) : interval;
            initialDelay = initialDelay == null ? Duration.ofSeconds(30) : initialDelay;
        }
    }

    @Bean
    SellerOrgSource sellerOrgSource(SellerSyncProperties props, IdentityProperties identity,
                                    JWKSource<SecurityContext> jwkSource, Clock clock) {
        SelfIssuedServiceToken token = new SelfIssuedServiceToken(new NimbusJwtEncoder(jwkSource), identity.issuer(),
                List.of(DIRECTORY_SCOPE), clock);
        return new HttpSellerOrgSource(props.sellerUrl(), token);
    }

    /**
     * Event consumption is at least once: a record whose processing fails (database unavailable, a lock conflict) is
     * retried with exponential back-off for up to an hour instead of Spring's default ten immediate attempts, after
     * which it is logged and skipped. Every consumer de-duplicates on the event id (platform inbox), and the reconcile
     * job repairs organisations whose events were skipped.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);
        backOff.setMaxElapsedTime(Duration.ofHours(1).toMillis());
        return new DefaultErrorHandler(backOff);
    }
}
