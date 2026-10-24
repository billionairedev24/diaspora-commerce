package com.afrotransact.inventory.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.afrotransact.inventory.adapter.out.carrier.HttpCarrierGateway;
import com.afrotransact.inventory.adapter.out.catalog.HttpUnitLabelClient;
import com.afrotransact.inventory.adapter.out.identity.ClientCredentialsTokenProvider;
import com.afrotransact.inventory.application.InventorySettings;
import com.afrotransact.inventory.domain.DefaultFcAssignmentPolicy;
import com.afrotransact.inventory.domain.DefaultFefoAllocator;
import com.afrotransact.inventory.domain.DefaultReconciler;
import com.afrotransact.inventory.domain.DefaultShelfLifePolicy;
import com.afrotransact.inventory.domain.FcAssignmentPolicy;
import com.afrotransact.inventory.domain.FefoAllocator;
import com.afrotransact.inventory.domain.Reconciler;
import com.afrotransact.inventory.domain.ShelfLifePolicy;
import com.afrotransact.inventory.port.CarrierGateway;
import com.afrotransact.inventory.port.ServiceTokenProvider;
import com.afrotransact.inventory.port.UnitLabelClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wiring of framework-free domain policies, settings and outbound adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryConfiguration {

    @Bean
    FefoAllocator fefoAllocator() {
        return new DefaultFefoAllocator();
    }

    @Bean
    ShelfLifePolicy shelfLifePolicy(InventoryProperties props) {
        return new DefaultShelfLifePolicy(props.shelfLife().minDays(), props.shelfLife().minFraction());
    }

    @Bean
    Reconciler reconciler() {
        return new DefaultReconciler();
    }

    @Bean
    InventorySettings inventorySettings(InventoryProperties props) {
        return new InventorySettings(props.retailOrgId(), props.reservationTtl(), props.fefoMinRemainingDays(),
                props.jobs().reservationSweepBatch(), props.storage().defaultUnitCubicFeet(), props.costCurrencies());
    }

    @Bean
    FcAssignmentPolicy fcAssignmentPolicy() {
        return new DefaultFcAssignmentPolicy();
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(RestClient.Builder builder, InventoryProperties props, JsonMapper json,
                                              Clock clock) {
        InventoryProperties.Auth auth = props.auth();
        return new ClientCredentialsTokenProvider(builder.clone().requestFactory(factory(Duration.ofSeconds(10)))
                .build(), auth.tokenUri(), auth.clientId(), auth.clientSecret(), auth.scopes(), json, clock);
    }

    @Bean
    UnitLabelClient unitLabelClient(RestClient.Builder builder, InventoryProperties props,
                                    ServiceTokenProvider tokens, JsonMapper json) {
        return new HttpUnitLabelClient(builder.clone().baseUrl(props.catalog().baseUrl())
                .requestFactory(factory(props.catalog().timeout())).build(), tokens, json);
    }

    @Bean
    CarrierGateway carrierGateway(RestClient.Builder builder, InventoryProperties props, ServiceTokenProvider tokens,
                                  JsonMapper json) {
        return new HttpCarrierGateway(builder.clone().baseUrl(props.carrierGateway().baseUrl())
                .requestFactory(factory(props.carrierGateway().timeout())).build(), tokens, json);
    }

    private static JdkClientHttpRequestFactory factory(Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Bean
    TransactionOperations inventoryTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
