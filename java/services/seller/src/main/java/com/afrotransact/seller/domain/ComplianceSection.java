package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Step "catalog & compliance": categories, permits, service licences, insurance, agreements and W-9.
 *
 * @param agreements the legal documents accepted, each with the version that was shown (empty until accepted)
 */
public record ComplianceSection(List<String> categories, FoodPermit foodPermit, Boolean beautyLabellingConfirmed,
                                List<ServiceLicence> services, String insuranceDocumentAssetId,
                                Boolean agreementsAccepted, Boolean w9Certified, String w9SignerName,
                                List<AcceptedAgreement> agreements) {

    /** Legal documents a seller accepts in onboarding (content-service keys). */
    public static final List<String> SELLER_AGREEMENTS = List.of("seller-terms", "payments-terms", "data-processing");

    /** Legal documents a provider accepts in onboarding (content-service keys). */
    public static final List<String> PROVIDER_AGREEMENTS = List.of("provider-terms", "payments-terms",
            "data-processing");

    /** Kinds of food permit the wizard offers. */
    public static final Set<String> PERMIT_TYPES = Set.of("FOOD_ESTABLISHMENT", "COTTAGE_FOOD",
            "FDA_FACILITY", "FOOD_HANDLER");

    public ComplianceSection {
        categories = categories == null ? List.of() : List.copyOf(categories);
        services = services == null ? List.of() : List.copyOf(services);
        agreements = agreements == null ? List.of() : List.copyOf(agreements);
    }

    /** Without agreements (no document versions recorded). */
    public ComplianceSection(List<String> categories, FoodPermit foodPermit, Boolean beautyLabellingConfirmed,
                             List<ServiceLicence> services, String insuranceDocumentAssetId,
                             Boolean agreementsAccepted, Boolean w9Certified, String w9SignerName) {
        this(categories, foodPermit, beautyLabellingConfirmed, services, insuranceDocumentAssetId, agreementsAccepted,
                w9Certified, w9SignerName, List.of());
    }

    /**
     * A legal document accepted in onboarding.
     *
     * @param key        document key, for example {@code seller-terms}
     * @param version    version shown to the applicant when they accepted, for example {@code 2.4}
     * @param acceptedOn date of acceptance
     */
    public record AcceptedAgreement(String key, String version, LocalDate acceptedOn) {
    }

    /**
     * Food establishment / cottage food permit for groceries and perishables.
     *
     * @param permitType {@link #PERMIT_TYPES}: county food establishment permit, state cottage-food permit, FDA food
     *                   facility registration or food handler certificate
     */
    public record FoodPermit(String permitNumber, String issuingAuthority, LocalDate expiresOn,
                             String documentAssetId, String permitType) {
    }

    /** A service the provider offers; regulated services need a licence and its document. */
    public record ServiceLicence(String serviceName, boolean regulated, String licenceNumber, String issuingState,
                                 LocalDate expiresOn, String documentAssetId) {
    }
}
