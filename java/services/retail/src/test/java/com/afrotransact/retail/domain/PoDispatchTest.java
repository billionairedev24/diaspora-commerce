package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S225 delivery rules: retries with backoff, permanent failures, buyer retry, contact and EDI settings. */
class PoDispatchTest {

    private static final Instant NOW = Fixtures.NOW;

    @Test
    void transientFailuresBackOffUntilTheAttemptsRunOut() {
        PoDispatch d = PoDispatch.pending(UUID.randomUUID(), "PO-1P-1", DispatchChannel.EMAIL, "a@b.example", NOW);
        PoDispatch first = d.attemptFailed("media down", false, 3, NOW);
        assertThat(first.status()).isEqualTo(DispatchStatus.PENDING);
        assertThat(first.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        PoDispatch second = first.attemptFailed("media down", false, 3, NOW);
        assertThat(second.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        PoDispatch third = second.attemptFailed("media down", false, 3, NOW);
        assertThat(third.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(third.attempts()).isEqualTo(3);

        PoDispatch retried = third.retry(NOW);
        assertThat(retried.status()).isEqualTo(DispatchStatus.PENDING);
        assertThat(retried.attempts()).isZero();
        assertThatThrownBy(() -> retried.retry(NOW)).isInstanceOf(RetailStateConflict.class);
    }

    @Test
    void permanentFailuresFailAtOnceAndBackoffIsCapped() {
        PoDispatch d = PoDispatch.pending(UUID.randomUUID(), "PO-1P-1", DispatchChannel.EDI, "ACC", NOW);
        assertThat(d.attemptFailed("disabled", true, 5, NOW).status()).isEqualTo(DispatchStatus.FAILED);
        PoDispatch many = d;
        for (int i = 0; i < 9; i++) {
            many = many.attemptFailed("down", false, 20, NOW);
        }
        assertThat(many.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void purchaseOrdersGoToTheSalesContactWithAnEmail() {
        Supplier s = Supplier.onboard(UUID.randomUUID(), "SUP-ACC", "Accra Pack", "GH", "Accra", "GHS", "Net 30", 35,
                List.of(), List.of(new SupplierContact("Kofi", "Quality", "kofi@x.example", null),
                        new SupplierContact("Ama", "Sales", null, "+233"),
                        new SupplierContact("Efua", "Sales & orders", "efua@x.example", null)), NOW);
        assertThat(s.purchaseOrderContact()).map(SupplierContact::email).contains("efua@x.example");
        Supplier noSales = s.update("Accra Pack", "Accra", "GHS", "Net 30", 35, List.of(),
                List.of(new SupplierContact("Kofi", "Quality", "kofi@x.example", null)), NOW);
        assertThat(noSales.purchaseOrderContact()).map(SupplierContact::email).contains("kofi@x.example");
        Supplier none = s.update("Accra Pack", "Accra", "GHS", "Net 30", 35, List.of(),
                List.of(new SupplierContact("Ama", "Sales", " ", null)), NOW);
        assertThat(none.purchaseOrderContact()).isEmpty();
    }

    @Test
    void ediSettingsAreValidated() {
        UUID id = UUID.randomUUID();
        SupplierEdiProfile ok = new SupplierEdiProfile(id, true, EdiProtocol.SFTP, " accra01 ",
                "sftp://edi.example/in", "buyer", NOW, 0);
        assertThat(ok.interchangeId()).isEqualTo("ACCRA01");
        assertThatThrownBy(() -> new SupplierEdiProfile(id, true, EdiProtocol.AS2, "ACCRA01", "sftp://edi.example",
                "buyer", NOW, 0)).isInstanceOf(RetailRuleViolation.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> new SupplierEdiProfile(id, true, EdiProtocol.SFTP, "WAY-TOO-LONG-ISA-ID",
                "sftp://edi.example", "buyer", NOW, 0)).isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> new SupplierEdiProfile(id, true, null, "A", "sftp://x", "buyer", NOW, 0))
                .isInstanceOf(RetailRuleViolation.class);
    }
}
