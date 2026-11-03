package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.application.SettingsSummaryService.SettingLine;
import com.afrotransact.payments.config.PaymentsProperties;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PayoutPolicy;
import org.junit.jupiter.api.Test;

class SettingsSummaryServiceImplTest {

    private static SettingLine line(List<SettingLine> lines, String key) {
        return lines.stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow();
    }

    private static SettingsSummaryService service(String stripeKey) {
        PaymentsProperties props = mock(PaymentsProperties.class);
        PaymentsProperties.Stripe stripe = mock(PaymentsProperties.Stripe.class);
        when(stripe.secretKey()).thenReturn(stripeKey);
        when(props.stripe()).thenReturn(stripe);
        PayoutPolicy policy = mock(PayoutPolicy.class);
        when(policy.minimum("USD")).thenReturn(Money.of(2500, "USD"));
        PaymentsSettings settings = new PaymentsSettings("org_retail", Duration.ofDays(7), ZoneId.of("America/Chicago"),
                "USD", 5, Duration.ofHours(1));
        return new SettingsSummaryServiceImpl(settings, props, policy, mock(PaymentsAccess.class));
    }

    @Test
    void sayingWhatGovernsHoldsAndPayoutsInWords() {
        var lines = service("sk_test_abc").summary();

        assertThat(line(lines, "settlement.hold").value()).isEqualTo("7 days after delivery");
        assertThat(line(lines, "settlement.hold").source()).isEqualTo("SETTLEMENT_HOLD");
        assertThat(line(lines, "payout.minimum").value()).isEqualTo("$25.00");
        assertThat(line(lines, "settlement.recheck").value()).isEqualTo("1 hour");
        assertThat(line(lines, "stripe.mode").value()).startsWith("Test");
        assertThat(Optional.of(line(lines, "payout.daily").value())).get().asString().contains("America/Chicago");
    }

    @Test
    void theStripeModeFollowsTheKey() {
        assertThat(line(service("sk_live_abc").summary(), "stripe.mode").value()).startsWith("Live");
        assertThat(line(service("").summary(), "stripe.mode").value()).startsWith("Local");
    }
}
