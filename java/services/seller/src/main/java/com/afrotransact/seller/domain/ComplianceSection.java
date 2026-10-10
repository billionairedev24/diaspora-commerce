package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.List;

/** Step "catalog & compliance": categories, permits, service licences, insurance, agreements and W-9. */
public record ComplianceSection(List<String> categories, FoodPermit foodPermit, Boolean beautyLabellingConfirmed,
                                List<ServiceLicence> services, String insuranceDocumentAssetId,
                                Boolean agreementsAccepted, Boolean w9Certified, String w9SignerName) {

    public ComplianceSection {
        categories = categories == null ? List.of() : List.copyOf(categories);
        services = services == null ? List.of() : List.copyOf(services);
    }

    /** Food establishment / cottage food permit for groceries and perishables. */
    public record FoodPermit(String permitNumber, String issuingAuthority, LocalDate expiresOn,
                             String documentAssetId) {
    }

    /** A service the provider offers; regulated services need a licence and its document. */
    public record ServiceLicence(String serviceName, boolean regulated, String licenceNumber, String issuingState,
                                 LocalDate expiresOn, String documentAssetId) {
    }
}
