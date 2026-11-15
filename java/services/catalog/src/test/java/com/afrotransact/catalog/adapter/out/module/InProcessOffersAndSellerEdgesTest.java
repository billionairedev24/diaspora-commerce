package com.afrotransact.catalog.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.seller.api.SellerLookupApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Catalog's in-process adapters call as catalog's principal, map what comes back like the HTTP adapters, and fail closed: a
 * refusal (the scope is missing) or a failure of the callee is the same "unavailable" the HTTP adapter throws.
 */
class InProcessOffersAndSellerEdgesTest {

    private static final CallerPrincipal CATALOG = new CallerPrincipal("catalog", "catalog-service",
            Set.of("offers:write"));

    private static StaticListableBeanFactory beans(Object api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("callers", (CallerPrincipals) module -> CATALOG);
        return beans;
    }

    @Test
    void writesBatchesAsCatalogAndMapsTheResults() {
        OffersApi api = new FakeOffers() {
            @Override
            public List<RowResult> upsert(String org, UUID importId, String by, List<OfferTerms> rows) {
                assertThat(InProcessCalls.require()).isEqualTo(CATALOG);
                return rows.stream().map(r -> new RowResult(r.rowKey(), "CREATED", UUID.randomUUID(), r.atin(), "ACTIVE",
                        null, null)).toList();
            }
        };
        StaticListableBeanFactory beans = beans(api);
        OfferWriter writer = new InProcessOfferWriter(beans.getBeanProvider(OffersApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        List<OfferWriter.Result> results = writer.upsert("org", null, null, List.of(new OfferWriter.OfferTerms("r1",
                "AT00000000", "SKU", "NEW", 100, null, null, "SELLER", 1, 1, null, null, null)));
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.rowKey()).isEqualTo("r1");
            assertThat(r.outcome()).isEqualTo("CREATED");
        });
    }

    @Test
    void aRefusalAShortAnswerOrAFailureIsOffersUnavailable() {
        OffersApi refusing = new FakeOffers() {
            @Override
            public List<RowResult> upsert(String org, UUID importId, String by, List<OfferTerms> rows) {
                throw new ModuleApiException(403, "auth.missing-permission", "Missing offers:write");
            }

            @Override
            public boolean hasOpenOffer(String org, String atin) {
                throw new IllegalStateException("down");
            }
        };
        StaticListableBeanFactory beans = beans(refusing);
        OfferWriter writer = new InProcessOfferWriter(beans.getBeanProvider(OffersApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThatThrownBy(() -> writer.upsert("org", null, null, List.of(new OfferWriter.OfferTerms("r1", "AT00000000",
                "SKU", "NEW", 100, null, null, "SELLER", 1, 1, null, null, null))))
                .isInstanceOf(OfferWriter.OffersUnavailable.class).hasMessageContaining("Missing offers:write");
        SellerOffers presence = new InProcessSellerOffers(beans.getBeanProvider(OffersApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThatThrownBy(() -> presence.hasOpenOffer("org", "AT00000000"))
                .isInstanceOf(SellerOffers.OffersUnavailable.class);
        OffersApi shortAnswer = new FakeOffers();
        StaticListableBeanFactory shortBeans = beans(shortAnswer);
        OfferWriter shortWriter = new InProcessOfferWriter(shortBeans.getBeanProvider(OffersApi.class),
                shortBeans.getBeanProvider(CallerPrincipals.class), shortBeans);
        assertThatThrownBy(() -> shortWriter.setPricesAndStock("org", null, null, List.of(new OfferWriter.PriceStock("r1",
                "SKU", null, 1L, null, null)))).isInstanceOf(OfferWriter.OffersUnavailable.class)
                .hasMessageContaining("0 results for 1 rows");
    }

    @Test
    void sellingStatusIsMappedAndAFailureFailsClosed() {
        SellerLookupApi ok = new FakeSeller() {
            @Override
            public SellingStatus sellingStatus(String orgId, String categories, String brand, String type, String material,
                                               String gtin) {
                assertThat(categories).isEqualTo("food");
                assertThat(brand).isNull();
                return new SellingStatus("APPLY", "needs a permit", List.of(new Missing("DEPARTMENT", "food", "Food",
                        "APPLY", "NOT_APPLIED", "apply", true)), List.of("material"));
            }
        };
        StaticListableBeanFactory beans = beans(ok);
        SellingApprovals approvals = new InProcessSellingApprovals(beans.getBeanProvider(SellerLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        SellingApprovals.Status status = approvals.statusOf("org", new SellingApprovals.Query("food", " ", null, null, null));
        assertThat(status.level()).isEqualTo("APPLY");
        assertThat(status.sell()).isFalse();
        assertThat(status.missing()).singleElement().satisfies(m -> assertThat(m.scopeKey()).isEqualTo("food"));
        assertThat(status.pendingFacts()).containsExactly("material");

        SellerLookupApi down = new FakeSeller() {
            @Override
            public SellingStatus sellingStatus(String orgId, String categories, String brand, String type, String material,
                                               String gtin) {
                throw new ModuleApiException(403, "auth.missing-permission", "no scope");
            }
        };
        StaticListableBeanFactory downBeans = beans(down);
        SellingApprovals refused = new InProcessSellingApprovals(downBeans.getBeanProvider(SellerLookupApi.class),
                downBeans.getBeanProvider(CallerPrincipals.class), downBeans);
        assertThatThrownBy(() -> refused.statusOf("org", new SellingApprovals.Query(null, null, null, null, null)))
                .isInstanceOf(SellingApprovals.SellingUnavailable.class);
    }

    @Test
    void thePurchaseCheckMapsAndRefusesWhenOrdersDoesNotAnswer() {
        OrderLookupApi delivered = new FakeOrders() {
            @Override
            public PurchaseProof purchase(String customerId, String productAtin) {
                return new PurchaseProof(true, "o1", null, "s1", null);
            }
        };
        StaticListableBeanFactory beans = beans(delivered);
        PurchaseVerifier verifier = new InProcessPurchaseVerifier(beans.getBeanProvider(OrderLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(verifier.deliveredPurchase("c", "AT00000000")).hasValueSatisfying(p -> {
            assertThat(p.orderId()).isEqualTo("o1");
            assertThat(p.orderNumber()).isEqualTo("o1");
        });
        OrderLookupApi none = new FakeOrders() {
            @Override
            public PurchaseProof purchase(String customerId, String productAtin) {
                return new PurchaseProof(false, null, null, null, null);
            }
        };
        StaticListableBeanFactory noneBeans = beans(none);
        assertThat(new InProcessPurchaseVerifier(noneBeans.getBeanProvider(OrderLookupApi.class),
                noneBeans.getBeanProvider(CallerPrincipals.class), noneBeans).deliveredPurchase("c", "AT00000000"))
                .isEqualTo(Optional.empty());
        OrderLookupApi refusing = new FakeOrders() {
            @Override
            public PurchaseProof purchase(String customerId, String productAtin) {
                throw new ModuleApiException(403, "auth.missing-permission", "no scope");
            }
        };
        StaticListableBeanFactory refusingBeans = beans(refusing);
        PurchaseVerifier refused = new InProcessPurchaseVerifier(refusingBeans.getBeanProvider(OrderLookupApi.class),
                refusingBeans.getBeanProvider(CallerPrincipals.class), refusingBeans);
        assertThatThrownBy(() -> refused.deliveredPurchase("c", "AT00000000"))
                .isInstanceOf(PurchaseVerifier.PurchaseCheckUnavailable.class);
    }

    @Test
    void anInProcessAdapterWithoutItsCalleeStopsTheStartNamingBothModules() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new InProcessOfferWriter(empty.getBeanProvider(OffersApi.class),
                empty.getBeanProvider(CallerPrincipals.class), empty)).hasMessageContaining("'catalog'")
                .hasMessageContaining("'offers'").hasMessageContaining("at.catalog.offers.mode");
    }

    private static class FakeOffers implements OffersApi {
        @Override
        public List<RowResult> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows) {
            return List.of();
        }

        @Override
        public List<RowResult> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy,
                                                 List<PriceStock> rows) {
            return List.of();
        }

        @Override
        public boolean hasOpenOffer(String sellerOrgId, String atin) {
            return false;
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

    private static class FakeOrders implements OrderLookupApi {
        @Override
        public OrderView lookup(String idOrNumber) {
            return null;
        }

        @Override
        public PurchaseProof purchase(String customerId, String productAtin) {
            return null;
        }
    }
}
