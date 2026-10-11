package com.afrotransact.payments.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.afrotransact.payments.adapter.out.localtest.LocalTestStripeGateway;
import com.afrotransact.payments.adapter.out.stripe.StripeJavaGateway;
import com.afrotransact.payments.domain.DefaultLocalTestPaymentsPolicy;
import com.afrotransact.payments.domain.LocalTestPaymentsPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Payments without Stripe exist on the local profile only: asking for them on dev, uat or prod stops the service at
 * start-up, and a local run with a Stripe test key keeps using Stripe.
 */
class LocalTestPaymentsConfigurationTest {

    private static final String PLACEHOLDER = "sk_test_replace_with_your_stripe_test_key";

    private final LocalTestPaymentsPolicy policy = new DefaultLocalTestPaymentsPolicy();

    private static PaymentsProperties props(String mode, String key) {
        return new PaymentsProperties(null, null, null, null, null,
                new PaymentsProperties.Stripe(key, "whsec_x", null, null, null, null, null, null, mode, null), null, null);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void localWithoutAKeyUsesTheStandIn() {
        assertThat(PaymentsConfiguration.useLocalTest(policy, props("auto", PLACEHOLDER).stripe(), Set.of("local")))
                .isTrue();
        assertThat(PaymentsConfiguration.useLocalTest(policy, props("auto", null).stripe(), Set.of("local"))).isTrue();
        assertThat(PaymentsConfiguration.useLocalTest(policy, props("local-test", "sk_test_abc").stripe(),
                Set.of("local"))).isTrue();
        assertThat(new PaymentsConfiguration().stripeGateway(props("auto", PLACEHOLDER), policy, env("local"))).isInstanceOf(LocalTestStripeGateway.class);
    }

    @Test
    void localWithAStripeTestKeyUsesStripe() {
        assertThat(PaymentsConfiguration.useLocalTest(policy, props("auto", "sk_test_51abc").stripe(), Set.of("local")))
                .isFalse();
        assertThat(new PaymentsConfiguration().stripeGateway(props("auto", "sk_test_51abc"), policy, env("local"))).isInstanceOf(StripeJavaGateway.class);
    }

    @Test
    void stripeModeNeverUsesTheStandIn() {
        for (String profile : new String[] {"local", "dev", "uat", "prod"}) {
            assertThat(PaymentsConfiguration.useLocalTest(policy, props("stripe", PLACEHOLDER).stripe(),
                    Set.of(profile))).as(profile).isFalse();
            assertThat(PaymentsConfiguration.useLocalTest(policy, props(null, null).stripe(), Set.of(profile)))
                    .as(profile + " default mode").isFalse();
        }
    }

    @Test
    void devUatAndProdRefuseToStartWithoutStripe() {
        for (String profile : new String[] {"dev", "uat", "prod"}) {
            for (String mode : new String[] {"local-test", "auto", "LOCAL-TEST"}) {
                assertThatThrownBy(() -> PaymentsConfiguration.useLocalTest(policy, props(mode, null).stripe(),
                        Set.of(profile)))
                        .as(mode + " on " + profile).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("local profile only");
                assertThatThrownBy(() -> PaymentsConfiguration.useLocalTest(policy, props(mode, null).stripe(),
                        Set.of("local", profile)))
                        .as(mode + " on local + " + profile).isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(() -> new PaymentsConfiguration().stripeGateway(props("local-test", null), policy,
                    env(profile)))
                    .as("gateway bean on " + profile).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anUnknownModeIsAConfigurationError() {
        assertThatThrownBy(() -> PaymentsConfiguration.useLocalTest(policy, props("fake", null).stripe(),
                Set.of("local"))).isInstanceOf(IllegalArgumentException.class);
    }
}
