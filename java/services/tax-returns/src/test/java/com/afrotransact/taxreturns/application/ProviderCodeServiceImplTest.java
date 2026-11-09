package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderCodeServiceImplTest {

    private static final String CODE = "AT.RICE_GRAINS.STANDARD";

    private final ProviderCodeRepository codes = mock(ProviderCodeRepository.class);
    private final TaxProfileRepository profiles = mock(TaxProfileRepository.class);
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final TestRules rules = new TestRules();
    private final ProviderCodeService service = new ProviderCodeServiceImpl(codes, profiles, rules.catalog,
            new ClassificationEvents(publisher, mock(TaxTelemetry.class)), TestActors.staff("tax:classify-admin"),
            TestRules.CLOCK);

    private ProviderCode entry(TaxProviderId provider, String code, boolean verified, Outcome outcome) {
        return new ProviderCode(CODE, "rice-grains", outcome, provider, code, code != null, verified, null, 1, "seed",
                TestRules.NOW);
    }

    @Test
    void settingACodeIsVersionedAuditedAndStartsANewRuleVersion() {
        when(codes.find(CODE, TaxProviderId.AVALARA)).thenReturn(Optional.of(entry(TaxProviderId.AVALARA, null, false,
                Outcome.EXEMPT)));
        when(profiles.newRuleVersion(anyString(), anyString(), any())).thenReturn("tx-1+1");

        ProviderCode saved = service.update(CODE, new ProviderCodeService.Update(TaxProviderId.AVALARA, "PF050000", null,
                "Taken from the Avalara tax code list"));

        assertThat(saved.providerCode()).isEqualTo("PF050000");
        assertThat(saved.verified()).isFalse();
        assertThat(saved.mapVersion()).isEqualTo(2);
        verify(codes).update(saved);
        ArgumentCaptor<TaxProfileRepository.AuditRow> audit = ArgumentCaptor.forClass(TaxProfileRepository.AuditRow.class);
        verify(profiles).audit(audit.capture());
        assertThat(audit.getValue().kind()).isEqualTo("PROVIDER_CODE_MAP");
        assertThat(audit.getValue().ruleVersion()).isEqualTo("tx-1+1");
        assertThat(audit.getValue().after()).containsEntry("providerCode", "PF050000");
        verify(publisher).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("rice-grains"), any());
    }

    @Test
    void changingACodeResetsVerificationUnlessTheCallerVerifiesItInTheSameRequest() {
        when(codes.find(CODE, TaxProviderId.STRIPE)).thenReturn(Optional.of(entry(TaxProviderId.STRIPE, "txcd_40010002",
                true, Outcome.EXEMPT)));

        assertThat(service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, "txcd_40010003", null,
                "Stripe suggested another code")).verified()).isFalse();
        assertThat(service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, "txcd_40010003", true,
                "Checked in the shadow report")).verified()).isTrue();
    }

    @Test
    void anUnchangedEntryIsNotANewRuleVersion() {
        when(codes.find(CODE, TaxProviderId.STRIPE)).thenReturn(Optional.of(entry(TaxProviderId.STRIPE, "txcd_40010002",
                false, Outcome.EXEMPT)));

        service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, "txcd_40010002", null, "No real change"));

        verify(profiles, never()).newRuleVersion(anyString(), anyString(), any());
        verify(codes, never()).update(any());
    }

    @Test
    void codesAreValidatedAndABlockedOutcomeStaysUnmapped() {
        when(codes.find(CODE, TaxProviderId.STRIPE)).thenReturn(Optional.of(entry(TaxProviderId.STRIPE, null, false,
                Outcome.EXEMPT)));
        when(codes.find("AT.BUNDLE", TaxProviderId.STRIPE)).thenReturn(Optional.of(entry(TaxProviderId.STRIPE, null,
                false, Outcome.BLOCKED)));

        assertThatThrownBy(() -> service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, "not-a-code",
                null, "Typing a wrong code"))).isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("txcd_");
        assertThatThrownBy(() -> service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, null, true,
                "Verify nothing at all"))).isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("no code");
        assertThatThrownBy(() -> service.update("AT.BUNDLE", new ProviderCodeService.Update(TaxProviderId.STRIPE,
                "txcd_40010002", null, "Mapping a blocked outcome"))).isInstanceOf(ReturnRuleViolation.class)
                .hasMessageContaining("blocked outcome");
        assertThatThrownBy(() -> service.update("AT.NOPE", new ProviderCodeService.Update(TaxProviderId.AVALARA, "X1",
                null, "Unknown outcome code"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void clearingAMappingIsAllowed() {
        when(codes.find(CODE, TaxProviderId.STRIPE)).thenReturn(Optional.of(entry(TaxProviderId.STRIPE, "txcd_40010002",
                true, Outcome.EXEMPT)));

        ProviderCode cleared = service.update(CODE, new ProviderCodeService.Update(TaxProviderId.STRIPE, null, null,
                "Stripe retired the code"));

        assertThat(cleared.providerCode()).isNull();
        assertThat(cleared.active()).isFalse();
        assertThat(cleared.verified()).isFalse();
    }
}
