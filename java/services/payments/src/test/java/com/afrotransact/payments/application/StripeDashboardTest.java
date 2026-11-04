package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.afrotransact.payments.config.PaymentsProperties;
import org.junit.jupiter.api.Test;

class StripeDashboardTest {

    private static StripeDashboard with(String key) {
        PaymentsProperties props = mock(PaymentsProperties.class);
        PaymentsProperties.Stripe stripe = mock(PaymentsProperties.Stripe.class);
        when(stripe.secretKey()).thenReturn(key);
        when(props.stripe()).thenReturn(stripe);
        return new StripeDashboard(props);
    }

    @Test
    void aTestKeyLinksIntoTestMode() {
        StripeDashboard d = with("sk_test_abc");
        assertThat(d.url("tr_1ABCdefGHI")).isEqualTo("https://dashboard.stripe.com/test/connect/transfers/tr_1ABCdefGHI");
        assertThat(d.url("pi_3XYZ")).isEqualTo("https://dashboard.stripe.com/test/payments/pi_3XYZ");
        assertThat(d.url("po_1UTB")).isEqualTo("https://dashboard.stripe.com/test/search?query=po_1UTB");
    }

    @Test
    void aLiveKeyLinksIntoLiveMode() {
        assertThat(with("sk_live_abc").url("acct_123")).isEqualTo("https://dashboard.stripe.com/connect/accounts/acct_123");
    }

    @Test
    void theLocalStandInHasNothingToOpen() {
        assertThat(with("sk_test_abc").url("tr_localtest_ab12")).isNull();
        assertThat(with("sk_test_abc").url(" ")).isNull();
    }
}
