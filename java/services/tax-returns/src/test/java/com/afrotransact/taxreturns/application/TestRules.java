package com.afrotransact.taxreturns.application;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.DecisionRow;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.profile.TaxMapFixture;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ExemptionCalendar;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import com.afrotransact.taxreturns.port.TaxProfileRepository;

/** The real 73 profiles of tools/taxonomy behind in-memory ports, for application tests. */
final class TestRules {

    static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String RULES_V1 = "tx-test.1";

    final Map<String, TaxProfile> profiles = new LinkedHashMap<>();
    final TaxProfileRepository profileRepo = mock(TaxProfileRepository.class);
    final ProviderCodeRepository codeRepo = mock(ProviderCodeRepository.class);
    final List<ProviderCode> codes = new ArrayList<>();
    final ProfileCatalog catalog;
    final DecisionEngine engine;
    String ruleVersion = RULES_V1;

    TestRules() {
        TaxMapFixture.profiles(TaxMapFixture.read("category_tax_map.json")).forEach(p -> profiles.put(p.categoryCode(), p));
        for (TaxProfile p : profiles.values()) {
            for (DecisionRow r : p.rows()) {
                r.providerCodes().forEach((provider, code) -> codes.add(new ProviderCode(r.internalTaxCode(),
                        p.categoryCode(), r.outcome(), TaxProviderId.valueOf(provider), code, true, false, null, 1,
                        "system:seed", NOW)));
            }
        }
        when(profileRepo.all()).thenAnswer(i -> new ArrayList<>(profiles.values()));
        when(profileRepo.find(anyString()))
                .thenAnswer(i -> Optional.ofNullable(profiles.get(i.<String>getArgument(0))));
        when(profileRepo.currentRuleVersion()).thenAnswer(i -> ruleVersion);
        when(codeRepo.forCodes(anyCollection())).thenAnswer(i -> {
            Collection<String> wanted = i.getArgument(0);
            return codes.stream().filter(c -> wanted.contains(c.internalTaxCode())).collect(Collectors.toList());
        });
        ExemptionCalendar calendar = (category, date) -> false;
        catalog = new ProfileCatalog(profileRepo, CLOCK);
        engine = new DecisionEngine(catalog, codeRepo, calendar, props(null), CLOCK);
    }

    TestRules status(String category, ProfileStatus status) {
        profiles.put(category, profiles.get(category).withStatus(status));
        ruleVersion = ruleVersion + "+";   // a real status change always starts a new rule version
        catalog.refresh();
        return this;
    }

    /** Marks every STRIPE code of the outcome verified (or not). */
    TestRules verify(String internalCode, boolean verified) {
        codes.replaceAll(c -> c.internalTaxCode().equals(internalCode) ? new ProviderCode(c.internalTaxCode(),
                c.categoryCode(), c.outcome(), c.provider(), c.providerCode(), c.active(), verified, c.verifiedNote(),
                c.mapVersion(), c.changedBy(), c.changedAt()) : c);
        return this;
    }

    static TaxReturnsProperties props(TaxReturnsProperties.Classification classification) {
        return new TaxReturnsProperties(new TaxReturnsProperties.Tax(null, null, null, classification, null), null, null,
                null, null);
    }
}
