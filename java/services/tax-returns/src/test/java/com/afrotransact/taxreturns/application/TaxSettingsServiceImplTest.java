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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.taxreturns.application.TaxSettingsService.TaxSettings;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.junit.jupiter.api.Test;

class TaxSettingsServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final TaxSettingsRepository repo = mock(TaxSettingsRepository.class);
    private final Access access = mock(Access.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ProviderCodeRepository codes = mock(ProviderCodeRepository.class);

    private TaxSettingsService service(String stripeKey, TaxReturnsProperties.TaxProvider provider) {
        return service(stripeKey, null, provider);
    }

    private TaxSettingsService service(String stripeKey, String avalaraAccount, TaxReturnsProperties.TaxProvider provider) {
        TaxReturnsProperties.AvalaraTax avalara = avalaraAccount == null ? null
                : new TaxReturnsProperties.AvalaraTax(avalaraAccount, "key", "ACME", null, null);
        TaxReturnsProperties props = new TaxReturnsProperties(new TaxReturnsProperties.Tax(provider,
                new TaxReturnsProperties.StripeTax(stripeKey, null, null), avalara, null, null), null, null, null, null);
        when(codes.countMapped(any())).thenReturn(105L);
        when(access.staff(anyString())).thenReturn(new Actor("u1", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("*")));
        when(repo.history(20)).thenReturn(List.of());
        TaxMethodResolver resolver = new TaxMethodResolver(repo, props, clock);
        return new TaxSettingsServiceImpl(repo, resolver, codes, props, access, clock);
    }

    @Test
    void ourOwnRulesApplyUntilStaffChooseOtherwise() {
        when(repo.method()).thenReturn(Optional.empty());
        when(repo.methodStamp()).thenReturn(Optional.empty());

        TaxSettings s = service("", TaxReturnsProperties.TaxProvider.RULES).current();

        assertThat(s.method()).isEqualTo(TaxMethod.RULES);
        assertThat(s.stripeAvailable()).isFalse();
    }

    @Test
    void stripeTaxIsRefusedWhileItIsNotSetUp() {
        when(repo.method()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("", TaxReturnsProperties.TaxProvider.RULES)
                .change(TaxMethod.STRIPE_TAX, null, "Turning on Stripe Tax for launch"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("Stripe Tax cannot be used");
        verify(repo, never()).changeMethod(any(), anyString(), anyString(), any());
    }

    @Test
    void aChangeNeedsAReasonAndIsRecorded() {
        when(repo.method()).thenReturn(Optional.empty());
        when(repo.methodStamp()).thenReturn(Optional.empty());
        TaxSettingsService service = service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES);

        assertThatThrownBy(() -> service.change(TaxMethod.STRIPE_TAX, null, "short")).isInstanceOf(ReturnRuleViolation.class);

        service.change(TaxMethod.STRIPE_TAX, null, "Stripe Tax registrations are done");
        verify(repo).changeMethod(eq(TaxMethod.STRIPE_TAX), eq("Stripe Tax registrations are done"), eq("u1"), eq(NOW));
    }

    @Test
    void choosingTheMethodAlreadyInForceChangesNothing() {
        when(repo.method()).thenReturn(Optional.empty());
        when(repo.methodStamp()).thenReturn(Optional.empty());

        service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES).change(TaxMethod.RULES, null, "Keeping our own rules");

        verify(repo, never()).changeMethod(any(), anyString(), anyString(), any());
    }

    @Test
    void avalaraIsRefusedUntilItsCredentialsAreSet() {
        when(repo.method()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES)
                .change(TaxMethod.AVALARA, null, "Cutting over to Avalara"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("Avalara cannot be used");
        assertThatThrownBy(() -> service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES)
                .change(TaxMethod.RULES, TaxProviderId.AVALARA, "Shadowing Avalara on real orders"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("Avalara cannot be used");
        verify(repo, never()).changeMethod(any(), anyString(), anyString(), any());
        verify(repo, never()).changeShadowProvider(any(), anyString(), anyString(), any());
    }

    @Test
    void aProviderWithNoMappedCodesCannotBecomeTheMethod() {
        when(repo.method()).thenReturn(Optional.empty());
        TaxSettingsService service = service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES);
        when(codes.countMapped(TaxProviderId.STRIPE)).thenReturn(0L);

        assertThatThrownBy(() -> service.change(TaxMethod.STRIPE_TAX, null, "Cutting over to Stripe Tax"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("no mapped tax codes");
    }

    @Test
    void theShadowProviderMustBeSetUpAndDifferFromTheMethod() {
        when(repo.method()).thenReturn(Optional.empty());
        when(repo.methodStamp()).thenReturn(Optional.empty());
        when(repo.shadowProvider()).thenReturn(Optional.empty());
        TaxSettingsService service = service("sk_test_x", "acct", TaxReturnsProperties.TaxProvider.RULES);

        service.change(TaxMethod.RULES, TaxProviderId.STRIPE, "Shadowing Stripe on real orders");
        verify(repo).changeShadowProvider(eq(TaxProviderId.STRIPE), eq("Shadowing Stripe on real orders"), eq("u1"),
                eq(NOW));
        verify(repo, never()).changeMethod(any(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.change(TaxMethod.STRIPE_TAX, TaxProviderId.STRIPE, "Shadow the method itself"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("already the method");
    }

    @Test
    void turningShadowOffIsARecordedChange() {
        when(repo.method()).thenReturn(Optional.empty());
        when(repo.methodStamp()).thenReturn(Optional.empty());
        when(repo.shadowProvider()).thenReturn(Optional.of(TaxProviderId.STRIPE));

        service("sk_test_x", TaxReturnsProperties.TaxProvider.RULES).change(TaxMethod.RULES, null,
                "Comparison finished, switching shadow off");

        verify(repo).changeShadowProvider(eq(null), anyString(), eq("u1"), eq(NOW));
    }
}
