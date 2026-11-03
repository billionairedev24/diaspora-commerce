package com.afrotransact.seller.adapter.out.localtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import org.junit.jupiter.api.Test;

class LocalTestStripeConnectTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    private final LocalTestStripeConnect connect = new LocalTestStripeConnect(clock);

    @Test
    void localAccountsSessionsAndVerification() {
        String account = connect.createAccount(new CreateConnectedAccount("org_1", "app_1", "US", "a@b.co",
                "individual", "k"));
        assertThat(account).startsWith("acct_local_");
        assertThat(connect.createAccount(new CreateConnectedAccount("org_1", "app_1", "US", null, "company", "k")))
                .as("idempotent per organisation").isEqualTo(account);
        AccountSession session = connect.createAccountSession(account, Set.of("account_onboarding"));
        assertThat(session.localTest()).isTrue();
        assertThat(session.clientSecret()).startsWith("local_test_session_");
        RequirementsSnapshot snap = connect.retrieveAccount(account);
        assertThat(snap.detailsSubmitted()).isTrue();
        assertThat(snap.chargesEnabled()).isTrue();
        assertThat(snap.payoutsEnabled()).isTrue();
        assertThat(snap.open()).isEmpty();
        assertThat(snap.payoutBank().last4()).as("the bank Stripe's onboarding would collect").isEqualTo("6789");
        assertThat(snap.payoutBank().usable()).isTrue();
    }

    @Test
    void realStripeAccountsAreRefused() {
        assertThatThrownBy(() -> connect.retrieveAccount("acct_1RealStripe")).isInstanceOf(GatewayException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    void planPaymentsStandInChargesUnlessTheMethodIsADecline() {
        LocalTestPlanPayments payments = new LocalTestPlanPayments();
        String customer = payments.ensureCustomer("org_1", "Ade", null);
        assertThat(customer).startsWith("cus_local_");
        PlanPaymentsGateway.Charge ok = payments.charge(customer, "pm_card_visa", 3900, "USD", "inv_1", "Plan",
                "inv-inv_1-a0");
        assertThat(ok.status()).isEqualTo(PlanPaymentsGateway.Status.SUCCEEDED);
        assertThat(payments.charge(customer, "pm_card_visa", 3900, "USD", "inv_1", "Plan", "inv-inv_1-a0")
                .reference()).as("same key, same attempt").isEqualTo(ok.reference());
        PlanPaymentsGateway.Charge declined = payments.charge(customer, "pm_card_chargeCustomerFail", 3900, "USD",
                "inv_2", "Plan", "inv-inv_2-a0");
        assertThat(declined.status()).isEqualTo(PlanPaymentsGateway.Status.FAILED);
        assertThat(declined.failureCode()).isEqualTo("card_declined");
    }
}
