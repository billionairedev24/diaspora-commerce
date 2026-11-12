package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.PageAlreadyExists;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S385 and S387: what the product writer asks before it creates a new page. */
class NewPageGuardImplTest {

    private static final CategoryRules RULES = new CategoryRules("CLO", "Clothing", "Fashion", "CLO", "Clothing", "open", null,
            false, List.of());

    private final ChangeRequestRepository changeRequests = mock(ChangeRequestRepository.class);
    private final GtinExemptionService exemptions = mock(GtinExemptionService.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private NewPageGuardImpl guard;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(new CatalogPolicy(List.of(), "mpn", 2, 3, 200, 3, List.of(), true, null, null, 0));
        guard = new NewPageGuardImpl(changeRequests, exemptions, settings);
    }

    // ---- a wrong match is open ------------------------------------------------------------------------------------

    @Test
    void whileAWrongMatchIsOpenForTheBarcodeANewPageIsRefusedWithThePlainAnswer() {
        when(changeRequests.existsOpenWrongMatch("00012345678905")).thenReturn(true);

        assertThatThrownBy(() -> guard.refuseWhileWrongMatchOpen("012345678905")).isInstanceOfSatisfying(ConflictException.class,
                e -> {
                    assertThat(e.code()).isEqualTo("catalog.wrong-match-open");
                    assertThat(e.getMessage()).isEqualTo("We are checking this barcode. We will tell you within 2 business days.");
                });
    }

    @Test
    void theBarcodeIsComparedAsItsFourteenDigitKeySoUpcAndEanMeet() {
        when(changeRequests.existsOpenWrongMatch("00012345678905")).thenReturn(true);

        assertThatThrownBy(() -> guard.refuseWhileWrongMatchOpen("0012345678905")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> guard.refuseWhileWrongMatchOpen("00012345678905")).isInstanceOf(ConflictException.class);
    }

    @Test
    void theNumberOfDaysInTheMessageIsTheSetting() {
        when(settings.current()).thenReturn(new CatalogPolicy(List.of(), "mpn", 5, 7, 200, 3, List.of(), true, null, null, 0));
        when(changeRequests.existsOpenWrongMatch(anyString())).thenReturn(true);

        assertThatThrownBy(() -> guard.refuseWhileWrongMatchOpen("012345678905")).hasMessageContaining("within 5 business days");
    }

    @Test
    void aBarcodeWithNoOpenReportIsLetThrough() {
        when(changeRequests.existsOpenWrongMatch(anyString())).thenReturn(false);

        assertThatCode(() -> guard.refuseWhileWrongMatchOpen("012345678905")).doesNotThrowAnyException();
    }

    @Test
    void anItemWithoutABarcodeAsksNothing() {
        guard.refuseWhileWrongMatchOpen(null);
        guard.refuseWhileWrongMatchOpen("  ");

        verify(changeRequests, never()).existsOpenWrongMatch(any());
    }

    // ---- an item without a barcode --------------------------------------------------------------------------------

    @Test
    void aListingWithBarcodesNeedsNoExemptionAndNothingIsLookedUp() {
        assertThat(guard.assessExemption(RULES, "dress", Map.of(), false)).isEmpty();

        verify(exemptions, never()).pageWithSamePartNumber(any());
        verify(exemptions, never()).requirement(any(), any(), any());
    }

    @Test
    void thePartNumberMatchComesFirstAndAnswersWithTheExistingPage() {
        Map<String, String> attributes = Map.of("brand_id", "b1", "mpn", "AB-123");
        when(exemptions.pageWithSamePartNumber(attributes)).thenReturn(Optional.of(page("AT10000001")));

        assertThatThrownBy(() -> guard.assessExemption(RULES, "dress", attributes, true))
                .isInstanceOfSatisfying(PageAlreadyExists.class, e -> assertThat(e.existingAtin()).isEqualTo("AT10000001"));
        verify(exemptions, never()).requirement(any(), any(), any());
    }

    @Test
    void withoutAMatchTheRequirementDecidesWhetherStaffMustApprove() {
        Map<String, String> attributes = Map.of("brand_id", "b1");
        when(exemptions.pageWithSamePartNumber(attributes)).thenReturn(Optional.empty());
        when(exemptions.requirement(RULES, "dress", attributes)).thenReturn(Optional.of(ExemptionRequirement.KNOWN_GS1_BRAND));

        assertThat(guard.assessExemption(RULES, "dress", attributes, true)).contains(ExemptionRequirement.KNOWN_GS1_BRAND);
    }

    @Test
    void anOpenCategoryGivesTheAtinAtOnce() {
        when(exemptions.pageWithSamePartNumber(any())).thenReturn(Optional.empty());
        when(exemptions.requirement(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(guard.assessExemption(RULES, "dress", Map.of(), true)).isEmpty();
    }

    @Test
    void theRequestIsRecordedForThePageThatWaits() {
        Product page = page("AT10000001");

        guard.exemptionRequested(page, "Handmade", ExemptionRequirement.BRAND_GATED_CATEGORY);

        verify(exemptions).requested(page, "Handmade", ExemptionRequirement.BRAND_GATED_CATEGORY);
    }

    private static Product page(String atin) {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");
        return new Product(UUID.randomUUID(), new Atin(atin), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), null, null,
                "custom", "Ankara tote", Map.of(), List.of(), "", List.of(), ProductStatus.LIVE, null, now, now, 0);
    }
}
