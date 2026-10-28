package com.afrotransact.payments.adapter.out.localtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import org.junit.jupiter.api.Test;

/** The local stand-in behaves like Stripe test mode with card 4242: confirm, authorise, capture per shipment. */
class LocalTestStripeGatewayTest {

    private final StripeGateway gateway = new LocalTestStripeGateway();

    private IntentSnapshot create(long amount, String key) {
        return gateway.createIntent(new StripeGateway.CreateIntent(Money.of(amount, "USD"), null, List.of(),
                "AfroTransact order o-1", Map.of("order_id", "o-1"), "o-1", false), key);
    }

    @Test
    void anIntentWaitsForThePaymentMethodThenReadsAsAuthorised() {
        IntentSnapshot created = create(4599, "o-1:create-intent");

        assertThat(created.id()).startsWith("pi_localtest_");
        assertThat(created.status()).isEqualTo("requires_payment_method");
        assertThat(created.clientSecret()).contains("_secret_");

        IntentSnapshot read = gateway.retrieveIntent(created.id());
        assertThat(read.status()).isEqualTo("requires_capture");
        assertThat(read.amountCapturable()).isEqualTo(4599);
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameIntent() {
        assertThat(create(1000, "o-2:create-intent").id()).isEqualTo(create(1000, "o-2:create-intent").id());
        assertThat(create(1000, "o-3:create-intent").id()).isNotEqualTo(create(1000, "o-4:create-intent").id());
    }

    @Test
    void partialCapturesThenAFinalOne() {
        IntentSnapshot pi = create(3000, "o-5:create-intent");
        gateway.retrieveIntent(pi.id());

        IntentSnapshot first = gateway.capture(pi.id(), Money.of(1000, "USD"), false, Map.of(), "o-5:capture:s1");
        assertThat(first.status()).isEqualTo("requires_capture");
        assertThat(first.amountReceived()).isEqualTo(1000);
        assertThat(first.amountCapturable()).isEqualTo(2000);
        assertThat(first.latestChargeId()).startsWith("ch_localtest_");

        IntentSnapshot last = gateway.capture(pi.id(), Money.of(2000, "USD"), true, Map.of(), "o-5:capture:s2");
        assertThat(last.status()).isEqualTo("succeeded");
        assertThat(last.amountReceived()).isEqualTo(3000);
        assertThat(gateway.chargeBalanceTransactions(last.latestChargeId())).isEmpty();
    }

    @Test
    void cancelAndReleaseFollowStripe() {
        IntentSnapshot untouched = create(500, "o-6:create-intent");
        assertThat(gateway.cancel(untouched.id(), "o-6:cancel").status()).isEqualTo("canceled");

        IntentSnapshot partly = create(800, "o-7:create-intent");
        gateway.retrieveIntent(partly.id());
        gateway.capture(partly.id(), Money.of(300, "USD"), false, Map.of(), "o-7:capture:s1");
        assertThat(gateway.releaseUncaptured(partly.id(), "o-7:release").status()).isEqualTo("succeeded");
    }

    @Test
    void anIntentFromBeforeARestartReadsAsAuthorised() {
        assertThat(gateway.retrieveIntent("pi_localtest_forgotten").status()).isEqualTo("requires_capture");
        assertThat(gateway.capture("pi_localtest_forgotten", Money.of(700, "USD"), true, Map.of(), "k").status())
                .isEqualTo("succeeded");
    }

    @Test
    void moneyMovementsSucceedAtOnce() {
        assertThat(gateway.refund("pi_x", Money.of(100, "USD"), Map.of(), "r1").status()).isEqualTo("succeeded");
        assertThat(gateway.createTransfer(Money.of(100, "USD"), "acct_1", "o-1", Map.of(), "t1").id())
                .startsWith("tr_localtest_");
        assertThat(gateway.createOffSessionIntent(new StripeGateway.OffSessionIntent(Money.of(900, "USD"), "cus_1",
                "pm_1", "late add", Map.of(), "o-8"), "o-8:create-intent").intent().status())
                .isEqualTo("requires_capture");
        assertThat(gateway.retrievePaymentMethod("pm_1").last4()).isEqualTo("4242");
    }

    /** Like setup_future_usage: the method of an intent created to save it is on the customer once authorised. */
    @Test
    void aMethodSavedWhilePayingIsListedOnTheCustomerOnceAuthorised() {
        assertThat(gateway.customerWallet("cus_localtest_a").methods()).isEmpty();
        IntentSnapshot saving = gateway.createIntent(new StripeGateway.CreateIntent(Money.of(2500, "USD"),
                "cus_localtest_a", List.of(), "AfroTransact order o-9", Map.of("order_id", "o-9"), "o-9", true),
                "o-9:create-intent");
        create(1200, "o-10:create-intent");
        assertThat(gateway.customerWallet("cus_localtest_a").methods()).as("not before the shopper confirms").isEmpty();

        gateway.retrieveIntent(saving.id());
        gateway.retrieveIntent(saving.id());

        StripeGateway.CustomerWalletSnapshot wallet = gateway.customerWallet("cus_localtest_a");
        assertThat(wallet.authoritative()).as("in memory only: payments never drops methods it lost").isFalse();
        assertThat(wallet.methods()).singleElement().satisfies(pm -> {
            assertThat(pm.id()).startsWith("pm_localtest_");
            assertThat(pm.customer()).isEqualTo("cus_localtest_a");
            assertThat(pm.last4()).isEqualTo("4242");
        });
        assertThat(wallet.defaultPaymentMethodId()).isEqualTo(wallet.methods().getFirst().id());
        assertThat(gateway.retrievePaymentMethod(wallet.defaultPaymentMethodId()).customer())
                .isEqualTo("cus_localtest_a");

        gateway.detachPaymentMethod(wallet.defaultPaymentMethodId(), "detach");
        assertThat(gateway.customerWallet("cus_localtest_a").methods()).isEmpty();
        assertThat(gateway.customerWallet("cus_localtest_a").defaultPaymentMethodId()).isNull();
    }
}
