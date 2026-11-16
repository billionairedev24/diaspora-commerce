package com.afrotransact.catalog.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.catalog.api.ModerationApi;
import com.afrotransact.catalog.application.ListingModerationService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.ReviewModerationService;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.port.SellerOffers.OffersUnavailable;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** The published moderation API: the same use cases as the moderation endpoints, the same refusals, the scope the services check. */
class ModerationApiAdapterTest {

    private static final CallerPrincipal TRUST_OPS = InProcessKit.caller("trust-ops", "catalog:moderate");
    private static final UUID ID = UUID.randomUUID();

    private final ProductService products = mock(ProductService.class);
    private final ListingModerationService listings = mock(ListingModerationService.class);
    private final ReviewModerationService reviews = mock(ReviewModerationService.class);
    private final ModerationApi api = new ModerationApiAdapter(products, listings, reviews, InProcessKit.ACTORS);

    private <T> T asTrustOps(Supplier<T> call) {
        return InProcessCalls.as(TRUST_OPS, "catalog", call);
    }

    @Test
    void everyDecisionRunsItsUseCaseAsTheCallingModule() {
        asTrustOps(() -> {
            api.approveProduct(ID);
            api.rejectProduct(ID, "blurry photo");
            api.takeDownProduct(ID, "COUNTERFEIT", "fake");
            api.approveReview(ID, null);
            api.rejectReview(ID, "SPAM", "ads");
            api.removeReview(ID, "ABUSIVE", null);
            return null;
        });
        verify(products).approve(ID);
        verify(products).reject(ID, "blurry photo");
        verify(listings).takeDown(ID, ListingReasonCode.COUNTERFEIT, "fake");
        verify(reviews).approve(ID, null);
        verify(reviews).reject(ID, ReviewReasonCode.SPAM, "ads");
        verify(reviews).remove(ID, ReviewReasonCode.ABUSIVE, null);
    }

    @Test
    void aCallerWithoutTheModerationScopeIsRefusedBecauseTheServiceChecksIt() {
        ModerationApi guarded = new ModerationApiAdapter(InProcessKit.guarded(ProductService.class, "catalog:moderate"),
                InProcessKit.guarded(ListingModerationService.class, "catalog:moderate"),
                InProcessKit.guarded(ReviewModerationService.class, "catalog:moderate"), InProcessKit.ACTORS);
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.approveProduct(ID));
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.rejectProduct(ID, "n"));
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.takeDownProduct(ID, "COUNTERFEIT", null));
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.approveReview(ID, null));
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.rejectReview(ID, "SPAM", null));
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> guarded.removeReview(ID, "SPAM", null));
        asTrustOps(() -> {
            guarded.approveProduct(ID);
            return null;
        });
    }

    @Test
    void theErrorsOfCatalogLeaveWithTheStatusAndCodeOfItsAdvice() {
        doThrow(new IllegalStateException("only a product in review can be approved")).when(products).approve(ID);
        assertThatThrownBy(() -> asTrustOps(() -> {
            api.approveProduct(ID);
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("catalog.invalid-transition");
        });
        UUID other = UUID.randomUUID();
        doThrow(new TaxNotReady("tax details missing", Map.of("taxCode", "required"), null)).when(products).approve(other);
        assertThatThrownBy(() -> asTrustOps(() -> {
            api.approveProduct(other);
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(422);
            assertThat(e.code()).isEqualTo("catalog.tax-not-ready");
            assertThat(e.details()).containsExactly("taxCode: required");
        });
        doThrow(new ReviewStateConflict("catalog.review-not-pending", "published")).when(reviews).reject(eq(ID), any(), any());
        assertThatThrownBy(() -> asTrustOps(() -> {
            api.rejectReview(ID, "SPAM", null);
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("catalog.review-not-pending");
        });
        when(products.approve(UUID.fromString("00000000-0000-0000-0000-000000000009")))
                .thenThrow(new OffersUnavailable("offers down", null));
        assertThatThrownBy(() -> asTrustOps(() -> {
            api.approveProduct(UUID.fromString("00000000-0000-0000-0000-000000000009"));
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.retryable()).isTrue();
        });
    }

    @Test
    void theRequestConstraintsOfTheEndpointsApplyHere() {
        for (Runnable bad : new Runnable[] {() -> api.rejectProduct(ID, " "), () -> api.rejectProduct(ID, null),
            () -> api.rejectProduct(ID, "x".repeat(1001)), () -> api.takeDownProduct(ID, "NOT_A_CODE", null),
            () -> api.takeDownProduct(ID, null, null), () -> api.rejectReview(ID, "NOPE", null)}) {
            assertThatThrownBy(() -> asTrustOps(() -> {
                bad.run();
                return null;
            })).isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        }
    }
}
