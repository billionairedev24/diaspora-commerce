package com.afrotransact.catalog.application;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.PageAlreadyExists;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.stereotype.Component;

@Component
class NewPageGuardImpl implements NewPageGuard {

    private final ChangeRequestRepository changeRequests;
    private final GtinExemptionService exemptions;
    private final CatalogSettingsService settings;

    NewPageGuardImpl(ChangeRequestRepository changeRequests, GtinExemptionService exemptions,
                     CatalogSettingsService settings) {
        this.changeRequests = changeRequests;
        this.exemptions = exemptions;
        this.settings = settings;
    }

    @Override
    public void refuseWhileWrongMatchOpen(String gtinDigits) {
        if (gtinDigits == null || gtinDigits.isBlank()) {
            return;
        }
        if (changeRequests.existsOpenWrongMatch(GtinReader.key14(gtinDigits))) {
            throw new ConflictException("catalog.wrong-match-open", "We are checking this barcode. We will tell you within "
                    + settings.current().reviewSlaBusinessDays() + " business days.");
        }
    }

    @Override
    public Optional<ExemptionRequirement> assessExemption(CategoryRules rules, String productTypeCode,
                                                          Map<String, String> attributes,
                                                          boolean anyVariantWithoutBarcode) {
        if (!anyVariantWithoutBarcode) {
            return Optional.empty();
        }
        exemptions.pageWithSamePartNumber(attributes).ifPresent(page -> {
            throw new PageAlreadyExists(page.atin().value());
        });
        return exemptions.requirement(rules, productTypeCode, attributes);
    }

    @Override
    public void exemptionRequested(Product page, String sellerReason, ExemptionRequirement requirement) {
        exemptions.requested(page, sellerReason, requirement);
    }
}
