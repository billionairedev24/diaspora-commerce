package com.afrotransact.seller.config;

import java.time.Clock;
import java.util.Set;

import com.afrotransact.seller.adapter.out.crypto.AesGcmFieldCipher;
import com.afrotransact.seller.adapter.out.media.HttpMediaClient;
import com.afrotransact.seller.adapter.out.phone.LibPhoneNumbers;
import com.afrotransact.seller.adapter.out.stripe.StripeBillingAdapter;
import com.afrotransact.seller.adapter.out.stripe.StripeConnectAdapter;
import com.afrotransact.seller.adapter.out.stripe.StripeWebhookAdapter;
import com.afrotransact.seller.adapter.out.usage.HttpAdSpendSource;
import com.afrotransact.seller.adapter.out.usage.HttpAiUsageSource;
import com.afrotransact.seller.adapter.out.usage.HttpStorageUsageSource;
import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.application.SellerSettings;
import com.afrotransact.seller.domain.ApplicationRules;
import com.afrotransact.seller.domain.DefaultApplicationRules;
import com.afrotransact.seller.domain.PhoneNumbers;
import com.afrotransact.seller.port.AdSpendSource;
import com.afrotransact.seller.port.AiUsageSource;
import com.afrotransact.seller.port.FieldCipher;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.seller.port.StorageUsageSource;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.afrotransact.seller.port.StripeWebhookParser;
import com.stripe.StripeClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wiring of framework-free domain policies, settings and outbound adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SellerProperties.class)
class SellerConfiguration {

    @Bean
    PhoneNumbers phoneNumbers() {
        return new LibPhoneNumbers();
    }

    @Bean
    ApplicationRules applicationRules(PhoneNumbers phones) {
        return new DefaultApplicationRules(phones);
    }

    @Bean
    SellerSettings sellerSettings(SellerProperties props) {
        return new SellerSettings(Set.copyOf(props.liveCountries()), props.stripe().sessionComponents());
    }

    @Bean
    FieldCipher fieldCipher(SellerProperties props) {
        return new AesGcmFieldCipher(props.crypto().activeKeyId(), props.crypto().keys());
    }

    @Bean
    StripeClient stripeClient(SellerProperties props) {
        SellerProperties.Stripe s = props.stripe();
        StripeClient.StripeClientBuilder b = StripeClient.builder().setApiKey(s.secretKey())
                .setMaxNetworkRetries(s.maxNetworkRetries());
        if (s.apiBase() != null && !s.apiBase().isBlank()) {
            b.setApiBase(s.apiBase());
            b.setConnectBase(s.apiBase());
        }
        if (s.filesBase() != null && !s.filesBase().isBlank()) {
            b.setFilesBase(s.filesBase());
        }
        return b.build();
    }

    @Bean
    StripeConnectGateway stripeConnectGateway(StripeClient client, JsonMapper mapper) {
        return new StripeConnectAdapter(client, mapper);
    }

    @Bean
    StripeBillingGateway stripeBillingGateway(StripeClient client, JsonMapper mapper) {
        return new StripeBillingAdapter(client, mapper);
    }

    @Bean
    StripeWebhookParser stripeWebhookParser(SellerProperties props, JsonMapper mapper) {
        return new StripeWebhookAdapter(props.stripe().connectWebhookSecret(), props.stripe().billingWebhookSecret(),
                mapper);
    }

    @Bean
    MediaClient mediaClient(SellerProperties props, JsonMapper mapper) {
        return new HttpMediaClient(props.media().baseUrl(), props.media().serviceToken(), mapper);
    }

    @Bean
    ServiceTokens sellerServiceTokens(SellerProperties props, Clock clock) {
        SellerProperties.Metering m = props.metering();
        return new ServiceTokens(RestClient.create(), m.tokenUri(), m.clientId(), m.clientSecret(), clock);
    }

    @Bean
    StorageUsageSource storageUsageSource(SellerProperties props, ServiceTokens tokens) {
        return new HttpStorageUsageSource(props.metering().inventoryUrl(), props.metering().timeout(), tokens);
    }

    @Bean
    AdSpendSource adSpendSource(SellerProperties props, ServiceTokens tokens) {
        return new HttpAdSpendSource(props.metering().contentUrl(), props.metering().timeout(), tokens);
    }

    @Bean
    AiUsageSource aiUsageSource(SellerProperties props, ServiceTokens tokens) {
        return new HttpAiUsageSource(props.metering().aiUrl(), props.metering().timeout(), tokens);
    }

    @Bean
    TransactionOperations sellerTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
