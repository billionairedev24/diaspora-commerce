package com.afrotransact.seller.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import com.afrotransact.seller.adapter.out.localtest.LocalTestPlanPayments;
import com.afrotransact.seller.adapter.out.localtest.LocalTestStripeConnect;
import com.afrotransact.seller.adapter.out.stripe.StripeConnectAdapter;
import com.afrotransact.seller.adapter.out.stripe.StripePlanPaymentsAdapter;
import com.afrotransact.seller.domain.DefaultLocalTestStripePolicy;
import com.afrotransact.seller.domain.LocalTestStripePolicy;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Onboarding without Stripe exists on the local profile only: asking for it on dev, uat or prod stops the service at
 * start-up, and a local run with a Stripe test key keeps using Stripe.
 */
class LocalTestStripeConfigurationTest {

    private final LocalTestStripePolicy policy = new DefaultLocalTestStripePolicy();
    private final SellerConfiguration config = new SellerConfiguration();
    private final StripeClient client = StripeClient.builder().setApiKey("sk_test_x").build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static SellerProperties.Stripe stripe(String mode, String key) {
        return new SellerProperties.Stripe(key, null, null, null, null, List.of(), null, mode);
    }

    private static SellerProperties props(String mode, String key) {
        return new SellerProperties(List.of("US"), stripe(mode, key), null, null, null, null, null, null, null);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void localWithoutAKeyUsesTheStandIns() {
        assertThat(SellerConfiguration.useLocalTest(policy, stripe("auto", ""), Set.of("local"))).isTrue();
        assertThat(SellerConfiguration.useLocalTest(policy, stripe("local-test", "sk_test_51abc"), Set.of("local")))
                .isTrue();
        assertThat(config.stripeConnectGateway(client, mapper, props("auto", ""), policy, env("local"),
                Clock.systemUTC())).isInstanceOf(LocalTestStripeConnect.class);
        assertThat(config.planPaymentsGateway(client, props("auto", ""), policy, env("local")))
                .isInstanceOf(LocalTestPlanPayments.class);
    }

    @Test
    void localWithAStripeTestKeyUsesStripe() {
        assertThat(SellerConfiguration.useLocalTest(policy, stripe("auto", "sk_test_51abc"), Set.of("local")))
                .isFalse();
        assertThat(config.stripeConnectGateway(client, mapper, props("auto", "sk_test_51abc"), policy, env("local"),
                Clock.systemUTC())).isInstanceOf(StripeConnectAdapter.class);
        assertThat(config.planPaymentsGateway(client, props("auto", "sk_test_51abc"), policy, env("local")))
                .isInstanceOf(StripePlanPaymentsAdapter.class);
    }

    @Test
    void stripeModeNeverUsesTheStandIn() {
        for (String profile : new String[] {"local", "dev", "uat", "prod"}) {
            assertThat(SellerConfiguration.useLocalTest(policy, stripe("stripe", ""), Set.of(profile))).as(profile)
                    .isFalse();
            assertThat(SellerConfiguration.useLocalTest(policy, stripe(null, ""), Set.of(profile)))
                    .as(profile + " default mode").isFalse();
        }
    }

    @Test
    void theStandInIsRefusedOutsideTheLocalProfile() {
        for (Set<String> profiles : List.of(Set.of("dev"), Set.of("uat"), Set.of("prod"), Set.of("local", "prod"),
                Set.of("default"))) {
            assertThatThrownBy(() -> SellerConfiguration.useLocalTest(policy, stripe("auto", ""), profiles))
                    .as(profiles.toString()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only allowed on the local profile");
        }
        assertThatThrownBy(() -> config.stripeConnectGateway(client, mapper, props("local-test", ""), policy,
                env("prod"), Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownModeIsAConfigurationError() {
        assertThatThrownBy(() -> policy.wantsLocalTest("sandbox", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
