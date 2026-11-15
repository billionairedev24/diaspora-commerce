package com.afrotransact.offers.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.offers.port.TaxDecisions;
import com.afrotransact.seller.api.SellerLookupApi;
import com.afrotransact.taxreturns.api.ListingDecisionApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Offers' in-process adapters call as offers' principal and keep the HTTP adapters' answers for 404, refusals and failures. */
class InProcessEdgesTest {

    private static final CallerPrincipal OFFERS = new CallerPrincipal("offers", "offers-service", Set.of("tax:read"));

    private static StaticListableBeanFactory beans(Object api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("callers", (CallerPrincipals) module -> OFFERS);
        return beans;
    }

    @Test
    void unknownVariantsAreEmptyAndEligibilityNeedsAnAnswer() {
        CatalogLookupApi api = new FakeCatalog() {
            @Override
            public Optional<LiveVariant> liveVariant(String atin) {
                assertThat(InProcessCalls.require()).isEqualTo(OFFERS);
                return "AT00000001".equals(atin) ? Optional.of(new LiveVariant(UUID.randomUUID(), "AT00000000", atin, "123",
                        null, "T", "org_p")) : Optional.empty();
            }

            @Override
            public Optional<OfferEligibility> offerEligibility(String atin, String org) {
                return Optional.empty();
            }
        };
        StaticListableBeanFactory beans = beans(api);
        CatalogClient client = new InProcessCatalogClient(beans.getBeanProvider(CatalogLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(client.liveVariant("AT00000001")).hasValueSatisfying(v -> assertThat(v.ownerOrgId()).isEqualTo("org_p"));
        assertThat(client.liveVariant("AT00000002")).isEmpty();
        assertThatThrownBy(() -> client.eligibility("AT00000001", "org"))
                .isInstanceOf(CatalogClient.CatalogUnavailableException.class);
    }

    @Test
    void aMissingPickupLocationIsEmptyAndAFailureIsUnavailable() {
        SellerLookupApi api = new FakeSeller() {
            @Override
            public Optional<ShipFromLocation> shipFromLocation(String id) {
                if ("boom".equals(id)) {
                    throw new ModuleApiException(403, "auth.missing-permission", "no scope");
                }
                return "known".equals(id) ? Optional.of(new ShipFromLocation("known", "org", "Houston", "TX", "US", "77002",
                        true)) : Optional.empty();
            }
        };
        StaticListableBeanFactory beans = beans(api);
        PickupLocationClient client = new InProcessPickupLocationClient(beans.getBeanProvider(SellerLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(client.find("known")).hasValueSatisfying(l -> assertThat(l.pickupEnabled()).isTrue());
        assertThat(client.find("none")).isEmpty();
        assertThatThrownBy(() -> client.find("boom")).isInstanceOf(PickupLocationClient.SellerUnavailableException.class);
    }

    @Test
    void aFailingEntitlementsReadNeverLimitsTheSeller() {
        SellerLookupApi failing = new FakeSeller() {
            @Override
            public ListingEntitlements listingEntitlements(String orgId) {
                throw new ModuleApiException(403, "auth.missing-permission", "no scope");
            }
        };
        StaticListableBeanFactory beans = beans(failing);
        assertThat(new InProcessListingEntitlementsClient(beans.getBeanProvider(SellerLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans).allowance("org"))
                .isEqualTo(ListingEntitlementsClient.ListingAllowance.UNLIMITED);
        SellerLookupApi capped = new FakeSeller() {
            @Override
            public ListingEntitlements listingEntitlements(String orgId) {
                return new ListingEntitlements("Starter", 25L, false);
            }
        };
        StaticListableBeanFactory cappedBeans = beans(capped);
        ListingEntitlementsClient.ListingAllowance allowance = new InProcessListingEntitlementsClient(
                cappedBeans.getBeanProvider(SellerLookupApi.class), cappedBeans.getBeanProvider(CallerPrincipals.class),
                cappedBeans).allowance("org");
        assertThat(allowance.planName()).isEqualTo("Starter");
        assertThat(allowance.allows(24)).isTrue();
        assertThat(allowance.allows(25)).isFalse();
    }

    @Test
    void aDecisionIsResolvedOnlyWhenTheStatusSaysSoAndAFailureHoldsTheOffer() {
        ListingDecisionApi api = new ListingDecisionApi() {
            @Override
            public DecisionState find(String atin, String org) {
                return new DecisionState("BLOCKED", true, List.of("why"));
            }

            @Override
            public DecisionState request(String atin, String org, Map<String, Object> behaviour) {
                throw new ModuleApiException(403, "auth.missing-permission", "no scope");
            }
        };
        StaticListableBeanFactory beans = beans(api);
        TaxDecisions tax = new InProcessTaxDecisions(beans.getBeanProvider(ListingDecisionApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(tax.find("AT00000000", "org").resolved()).isFalse();
        assertThatThrownBy(() -> tax.request("AT00000000", "org", null)).isInstanceOf(TaxDecisions.TaxUnavailable.class);
    }

    @Test
    void anAdapterWithoutItsCalleeStopsTheStartNamingBothModules() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new InProcessTaxDecisions(empty.getBeanProvider(ListingDecisionApi.class),
                empty.getBeanProvider(CallerPrincipals.class), empty)).hasMessageContaining("'offers'")
                .hasMessageContaining("'tax-returns'").hasMessageContaining("at.offers.taxreturns.mode");
    }

    private static class FakeCatalog implements CatalogLookupApi {
        @Override
        public Optional<LiveVariant> liveVariant(String variantAtin) {
            return Optional.empty();
        }

        @Override
        public Optional<LiveVariant> liveVariantByGtin(String gtin) {
            return Optional.empty();
        }

        @Override
        public Optional<OfferEligibility> offerEligibility(String variantAtin, String sellerOrgId) {
            return Optional.empty();
        }

        @Override
        public List<Department> departments() {
            return List.of();
        }

        @Override
        public List<String> bakeryClaimOrganisations() {
            return List.of();
        }
    }

    private static class FakeSeller implements SellerLookupApi {
        @Override
        public SellingStatus sellingStatus(String orgId, String categoryCodes, String brand, String productTypeCode,
                                           String material, String gtin) {
            return null;
        }

        @Override
        public ListingEntitlements listingEntitlements(String orgId) {
            return null;
        }

        @Override
        public Optional<ShipFromLocation> shipFromLocation(String locationId) {
            return Optional.empty();
        }
    }
}
