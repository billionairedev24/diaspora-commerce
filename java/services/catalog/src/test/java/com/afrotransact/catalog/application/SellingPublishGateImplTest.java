package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.catalog.port.SellingApprovals.Missing;
import com.afrotransact.catalog.port.SellingApprovals.Query;
import com.afrotransact.catalog.port.SellingApprovals.SellingUnavailable;
import com.afrotransact.catalog.port.SellingApprovals.Status;
import com.afrotransact.catalog.port.SellingHoldRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The publish gate: what it asks seller-service, when it refuses, and what the refusal says. */
class SellingPublishGateImplTest {

    private final SellingApprovals approvals = mock(SellingApprovals.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final SellingHoldRepository holds = mock(SellingHoldRepository.class);
    private final SellingPublishGateImpl gate = new SellingPublishGateImpl(approvals, categories, holds, true);
    private final ListingOwner seller = new ListingOwner(ListingOwnerKind.SELLER, "org_1");
    private final UUID typeId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();

    private CategoryNode node(UUID id, UUID parent, String code, CategoryLevel level) {
        return new CategoryNode(id, parent, code, code, level, level == CategoryLevel.PRODUCT_TYPE ? "whey" : null, code, 0, true, 0,
                null, null);
    }

    private void tree() {
        when(categories.findById(typeId)).thenReturn(Optional.of(node(typeId, categoryId, "whey-protein", CategoryLevel.PRODUCT_TYPE)));
        when(categories.findById(categoryId)).thenReturn(Optional.of(node(categoryId, departmentId, "dietary-supplements",
                CategoryLevel.CATEGORY)));
        when(categories.findById(departmentId)).thenReturn(Optional.of(node(departmentId, null, "health", CategoryLevel.DEPARTMENT)));
    }

    private static SellingPublishGate.Facts facts(UUID category) {
        return new SellingPublishGate.Facts(category, "whey", Map.of("brand", "Optimum", "material", "tub"), "00012345678905");
    }

    @Test
    void itAsksWithTheCategoryAndItsAncestorsTheBrandMaterialAndBarcode() {
        tree();
        when(approvals.statusOf(eq("org_1"), any())).thenReturn(new Status("SELL", null, List.of(), List.of()));
        gate.requirePublishable(seller, facts(typeId));
        ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
        verify(approvals).statusOf(eq("org_1"), q.capture());
        assertThat(q.getValue().categoryCodes()).isEqualTo("whey-protein,dietary-supplements,health");
        assertThat(q.getValue().brand()).isEqualTo("Optimum");
        assertThat(q.getValue().material()).isEqualTo("tub");
        assertThat(q.getValue().gtin()).isEqualTo("00012345678905");
        assertThat(q.getValue().productTypeCode()).isEqualTo("whey");
    }

    @Test
    void anApplyAnswerRefusesWithAPlainMessageAndALinkToApply() {
        tree();
        when(approvals.statusOf(eq("org_1"), any())).thenReturn(new Status("APPLY",
                "You need approval to sell in Dietary supplements. Apply once; after that every listing here goes live.",
                List.of(new Missing("CATEGORY", "dietary-supplements", "Dietary supplements", "APPLY", "NOT_APPLIED",
                        "You need approval", true)), List.of()));
        assertThatThrownBy(() -> gate.requirePublishable(seller, facts(typeId))).isInstanceOfSatisfying(
                SellingApprovalRequired.class, e -> {
                    assertThat(e.code()).isEqualTo("catalog.selling-approval-required");
                    assertThat(e.level()).isEqualTo("APPLY");
                    assertThat(e.getMessage()).contains("need approval").contains("saved as a draft");
                    assertThat(e.applyUrl()).isEqualTo("/selling-approvals?apply=CATEGORY:dietary-supplements");
                    assertThat(e.missing()).hasSize(1);
                });
    }

    @Test
    void notAvailableRefusesWithoutAnApplyLink() {
        tree();
        when(approvals.statusOf(eq("org_1"), any())).thenReturn(new Status("NOT_AVAILABLE", "Not available yet: bundles.",
                List.of(new Missing("CATEGORY", "gift-baskets-bundles", "Bundles", "NOT_AVAILABLE", "NOT_AVAILABLE", "x", false)),
                List.of()));
        assertThatThrownBy(() -> gate.requirePublishable(seller, facts(typeId))).isInstanceOfSatisfying(
                SellingApprovalRequired.class, e -> {
                    assertThat(e.level()).isEqualTo("NOT_AVAILABLE");
                    assertThat(e.applyUrl()).isEqualTo("/selling-approvals");
                });
    }

    @Test
    void whenSellerServiceIsDownItFailsClosed() {
        tree();
        when(approvals.statusOf(eq("org_1"), any())).thenThrow(new SellingUnavailable("down", null));
        assertThatThrownBy(() -> gate.requirePublishable(seller, facts(typeId))).isInstanceOf(SellingUnavailable.class);
    }

    @Test
    void platformListingsAndADisabledGateAreNeverAsked() {
        gate.requirePublishable(new ListingOwner(ListingOwnerKind.PLATFORM, "platform"), facts(typeId));
        new SellingPublishGateImpl(approvals, categories, holds, false).requirePublishable(seller, facts(typeId));
        verify(approvals, never()).statusOf(any(), any());
    }

    @Test
    void aHeldListingCannotBeResumedByTheSeller() {
        UUID id = UUID.randomUUID();
        when(holds.isHeld(id)).thenReturn(true);
        Product p = new Product(id, new Atin("AT00000001"), seller, null, null, "whey", "Whey protein 2 lb", Map.of(),
                List.of(), "", List.of(), ProductStatus.PAUSED, null, Instant.now(), Instant.now(), 0);
        assertThatThrownBy(() -> gate.requireResumable(p)).isInstanceOfSatisfying(SellingApprovalRequired.class,
                e -> assertThat(e.getMessage()).contains("Renew it"));
    }
}
