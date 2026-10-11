package com.afrotransact.payments.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * S282 contract test: the PaymentIntents checkout's Stripe Elements confirm (deferred intent, manual capture,
 * setup_future_usage when the shopper saves the method, automatic or restricted payment methods) are requests Stripe's
 * API accepts. stripe/stripe-mock validates every parameter against Stripe's OpenAPI specification and answers with
 * fixture objects, so this checks the request shapes of {@link StripeJavaGateway}, not Stripe's behaviour.
 */
class StripeMockContractIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("stripe/stripe-mock:v0.206.0");
    private static final GenericContainer<?> STRIPE_MOCK = new GenericContainer<>(IMAGE).withExposedPorts(12111)
            .waitingFor(Wait.forListeningPort());

    private static StripeGateway gateway;

    @BeforeAll
    static void start() {
        STRIPE_MOCK.start();
        StripeClient client = StripeClient.builder()
                .setApiKey("sk_test_123")
                .setApiBase("http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111))
                .build();
        gateway = new StripeJavaGateway(client, "http://localhost:3000/orders/paid");
    }

    @AfterAll
    static void stop() {
        STRIPE_MOCK.stop();
    }

    @Test
    void anOrderIntentForThePaymentElementWithSavingIsAValidStripeRequest() {
        StripeGateway.IntentSnapshot intent = gateway.createIntent(new StripeGateway.CreateIntent(Money.of(4599, "USD"),
                "cus_123", List.of(), "AfroTransact order o-1", Map.of("order_id", "o-1"), "o-1", true), "o-1:create-intent");

        assertThat(intent.id()).startsWith("pi_");
        assertThat(intent.clientSecret()).isNotBlank();
        assertThat(gateway.retrieveIntent(intent.id()).id()).isEqualTo(intent.id());
    }

    @Test
    void aPreorderIntentRestrictedToCardAndLinkIsAValidStripeRequest() {
        StripeGateway.IntentSnapshot intent = gateway.createIntent(new StripeGateway.CreateIntent(Money.of(2500, "USD"),
                null, List.of("card", "link"), "AfroTransact order o-2", Map.of("order_id", "o-2"), "o-2", false),
                "o-2:create-intent");

        assertThat(intent.id()).startsWith("pi_");
        assertThat(intent.clientSecret()).isNotBlank();
    }
}
