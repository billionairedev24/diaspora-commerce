package com.afrotransact.seller.adapter.out.localtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.StripePriceFacts;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.BankAccountDetails;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionSnapshot;
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
    }

    @Test
    void bankAccountsKeepOnlyTheLastFourAndNeverPrintTheNumbers() {
        String account = connect.createAccount(new CreateConnectedAccount("org_2", "app_2", "US", null, "company",
                "k"));
        BankAccountDetails bank = new BankAccountDetails("Ade LLC", "company", "021000021", "000123456789");
        assertThat(connect.attachBankAccount(account, bank).last4()).isEqualTo("6789");
        assertThat(bank.toString()).doesNotContain("000123456789").doesNotContain("021000021").contains("6789");
    }

    @Test
    void realStripeAccountsAreRefused() {
        assertThatThrownBy(() -> connect.retrieveAccount("acct_1RealStripe")).isInstanceOf(GatewayException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    void billingStandInStartsTrialsWithoutCharging() {
        LocalTestStripeBilling billing = new LocalTestStripeBilling(clock);
        String customer = billing.createCustomer("org_1", "Ade", null, "customer-org_1");
        assertThat(customer).startsWith("cus_local_");
        StripeBillingGateway.PlanPrice growth = new StripeBillingGateway.PlanPrice("price_local_g", "seller_growth", 1,
                Cadence.MONTHLY);
        SubscriptionSnapshot trial = billing.createSubscription(customer, growth,
                Instant.parse("2026-11-09T12:00:00Z"), "sub-org_1");
        assertThat(trial.status()).isEqualTo("trialing");
        assertThat(trial.trialEnd()).isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
        assertThat(billing.createSubscription(customer, growth, null, "sub-org_2").status()).isEqualTo("active");
    }

    @Test
    void billingStandInSyncsPricesAndAppliesFreeDaysCreditsAndCoupons() {
        LocalTestStripeBilling billing = new LocalTestStripeBilling(clock);
        String product = billing.createProduct("seller_growth", "AfroTransact Seller Growth", "plan-product-g");
        String v1 = billing.createPrice(product, 3900, Cadence.MONTHLY, "seller_growth_monthly", "seller_growth", 1,
                "k1");
        assertThat(billing.findPriceByLookupKey("seller_growth_monthly")).get()
                .satisfies(p -> assertThat(p.matches(3900, Cadence.MONTHLY)).isTrue());
        String v2 = billing.createPrice(product, 4900, Cadence.MONTHLY, "seller_growth_monthly", "seller_growth", 2,
                "k2");
        billing.archivePrice(v1);
        assertThat(billing.findPriceByLookupKey("seller_growth_monthly")).get().extracting(StripePriceFacts::id)
                .as("the lookup key moved to the new price").isEqualTo(v2);
        assertThat(billing.retrievePrice(v1)).get().extracting(StripePriceFacts::active).isEqualTo(false);

        SubscriptionSnapshot sub = billing.createSubscription("cus_local_1", new StripeBillingGateway.PlanPrice(v2,
                "seller_growth", 2, Cadence.MONTHLY), null, "sub-free-days");
        Instant later = Instant.parse("2026-12-10T12:00:00Z");
        assertThat(billing.setTrialEnd(sub.id(), later, "t").trialEnd()).isEqualTo(later);
        assertThat(billing.creditBalance("cus_local_1", 2500, "credit", "c1").endingBalanceCents()).isEqualTo(-2500);
        assertThat(billing.creditBalance("cus_local_1", 500, "credit", "c2").endingBalanceCents()).isEqualTo(-3000);
        assertThat(billing.createCoupon("at_x", BigDecimal.TEN, 3, "10% off", "cp")).isEqualTo("at_x");
        assertThat(billing.addDiscount(sub.id(), "at_x", "d").id()).isEqualTo(sub.id());
    }
}
