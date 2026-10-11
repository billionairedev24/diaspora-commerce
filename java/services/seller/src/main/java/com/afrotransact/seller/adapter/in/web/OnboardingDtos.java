package com.afrotransact.seller.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.seller.application.ApplicationPrefill;
import com.afrotransact.seller.application.ApplicationView;
import com.afrotransact.seller.application.DocumentRef;
import com.afrotransact.seller.application.PiiLevel;
import com.afrotransact.seller.domain.Address;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.BusinessDetailsInput;
import com.afrotransact.seller.domain.BusinessDetailsSection;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.Owner;
import com.afrotransact.seller.domain.PayoutInput;
import com.afrotransact.seller.domain.PayoutSection;
import com.afrotransact.seller.domain.PlanSection;
import com.afrotransact.seller.domain.StripeVerification;
import com.afrotransact.seller.domain.TimelineEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the onboarding wizard and the review dossier. */
final class OnboardingDtos {

    private OnboardingDtos() {
    }

    @Schema(name = "StartApplicationRequest")
    record StartRequest(@Schema(allowableValues = {"SELLER", "PROVIDER"}) @NotBlank String kind,
                        @Valid PrefillRequest prefill) {
    }

    @Schema(name = "ApplicationPrefill", description = "What the caller's account already knows (sent by "
            + "gateway-bff from identity's profile and address book). Used only when the application is created, and "
            + "only where the token's claims have nothing: name, email, phone (verified or not), the default address "
            + "as the business address, and a store name.")
    record PrefillRequest(@Size(max = 80) String givenName, @Size(max = 80) String familyName,
                          @Size(max = 254) String email, @Size(max = 32) String phone,
                          @Size(max = 120) String storeName, Address address) {

        ApplicationPrefill toPrefill() {
            return new ApplicationPrefill(givenName, familyName, email, phone, storeName, address);
        }
    }

    @Schema(name = "LaunchListRequest")
    record LaunchListRequest(@NotBlank @Size(min = 2, max = 2) String country, @NotBlank @Email String email,
                             Boolean shoppingUpdates) {
    }

    @Schema(name = "LaunchListResponse")
    record LaunchListResponse(String country, boolean added, String message) {
    }

    @Schema(name = "MarketsResponse")
    record MarketsResponse(List<String> liveCountries) {
    }

    @Schema(name = "BusinessStep", description = "Step 'business'. Non-live countries return 422 seller.country-not-live.")
    record BusinessStep(@Schema(allowableValues = {"PRODUCTS", "SERVICES", "BOTH"}) String offering,
                        @Schema(allowableValues = {"sole_proprietor", "single_member_llc", "multi_member_llc",
                            "corporation", "partnership", "nonprofit"}) String entityType,
                        @Schema(example = "US") String country, String storeName, String contactEmail,
                        @Schema(description = "National or international; stored as E.164") String phone,
                        String website) {

        BusinessSection toSection() {
            return new BusinessSection(Inputs.offering(offering), Inputs.entityType(entityType), country, storeName,
                    contactEmail, phone, website);
        }
    }

    @Schema(name = "BusinessDetailsStep", description = "Step 'business-details'; required fields depend on the entity "
            + "type. taxId (SSN/ITIN or EIN, with or without dashes) is encrypted; omit it to keep the stored one.")
    record BusinessDetailsStep(String legalFirstName, String legalLastName, String legalName, String dba,
                               String taxId, LocalDate dateOfBirth, String stateOfFormation,
                               LocalDate dateOfFormation, Address address, String formationDocumentAssetId,
                               String irsDeterminationLetterAssetId) {

        BusinessDetailsInput toInput() {
            return new BusinessDetailsInput(legalFirstName, legalLastName, legalName, dba, taxId, dateOfBirth,
                    stateOfFormation, dateOfFormation, address, formationDocumentAssetId,
                    irsDeterminationLetterAssetId);
        }
    }

    @Schema(name = "Owner", description = "A beneficial owner (25%+) or person with significant control, with their "
            + "home address. Identity and tax numbers are collected by Stripe's embedded onboarding, not here.")
    record OwnerDto(String firstName, String lastName, String title, String email, BigDecimal ownershipPercent,
                    Boolean significantControl, LocalDate dateOfBirth, Address address) {

        Owner toOwner() {
            return new Owner(firstName, lastName, title, email, ownershipPercent,
                    Boolean.TRUE.equals(significantControl), dateOfBirth, address);
        }
    }

    @Schema(name = "OwnersStep", description = "Step 'owners' (not for sole proprietors)")
    record OwnersStep(List<OwnerDto> owners) {
    }

    @Schema(name = "PayoutsStep", description = "Step 'payouts'. Only last 4 digits are stored. The bank account "
            + "goes to Stripe as the connected account's payout account: either externalAccountToken (btok_…, "
            + "Stripe.js) or the routing and account numbers, sent once to Stripe and never stored. The step is "
            + "complete only once Stripe accepted the account (externalAccountLinked).")
    record PayoutsStep(String accountHolderName, @Schema(allowableValues = {"checking", "savings"}) String accountType,
                       String bankName, String routingNumber, String accountNumber, String accountNumberConfirmation,
                       String externalAccountToken) {

        PayoutInput toInput() {
            return new PayoutInput(accountHolderName, accountType, bankName, routingNumber, accountNumber,
                    accountNumberConfirmation, externalAccountToken);
        }
    }

    @Schema(name = "PlanStep", description = "Step 'plan'. Cards are tokenised client-side (pm_…); only brand, last 4 "
            + "and expiry are stored. US_BANK_ACCOUNT charges the payout bank by ACH.")
    record PlanStep(String planCode, @Schema(allowableValues = {"MONTHLY", "QUARTERLY", "ANNUAL"}) String cadence,
                    @Schema(allowableValues = {"CARD", "US_BANK_ACCOUNT"}) String paymentMethodKind,
                    String stripePaymentMethodId, String cardBrand, String cardLast4, Integer cardExpMonth,
                    Integer cardExpYear) {

        PlanSection toSection() {
            return new PlanSection(planCode, Inputs.cadence(cadence), Inputs.paymentMethodKind(paymentMethodKind),
                    stripePaymentMethodId, cardBrand, cardLast4, cardExpMonth, cardExpYear);
        }
    }

    @Schema(name = "CatalogComplianceStep", description = "Step 'catalog-compliance'")
    record ComplianceStep(List<String> categories, ComplianceSection.FoodPermit foodPermit,
                          Boolean beautyLabellingConfirmed, List<ServiceLicenceDto> services,
                          String insuranceDocumentAssetId, Boolean agreementsAccepted, Boolean w9Certified,
                          String w9SignerName) {

        ComplianceSection toSection() {
            return new ComplianceSection(categories, foodPermit, beautyLabellingConfirmed,
                    services == null ? List.of() : services.stream().map(ServiceLicenceDto::toLicence).toList(),
                    insuranceDocumentAssetId, agreementsAccepted, w9Certified, w9SignerName);
        }
    }

    @Schema(name = "ServiceLicence", description = "A service offered; regulated services need licence number, "
            + "issuing state, expiry (expired licences are rejected) and the licence document")
    record ServiceLicenceDto(String serviceName, Boolean regulated, String licenceNumber, String issuingState,
                             LocalDate expiresOn, String documentAssetId) {

        ComplianceSection.ServiceLicence toLicence() {
            return new ComplianceSection.ServiceLicence(serviceName, Boolean.TRUE.equals(regulated), licenceNumber,
                    issuingState, expiresOn, documentAssetId);
        }
    }

    @Schema(name = "BusinessDetails", description = "Tax ID is masked (last 4) unless the reviewer has "
            + "applications:pii; dates of birth are year-only for masked views.")
    record BusinessDetailsView(String legalFirstName, String legalLastName, String legalName, String dba,
                               String taxIdType, String taxIdMasked, String taxId, String dateOfBirth,
                               Integer birthYear, String stateOfFormation, LocalDate dateOfFormation,
                               Address address, String formationDocumentAssetId,
                               String irsDeterminationLetterAssetId) {
    }

    @Schema(name = "OwnerView")
    record OwnerView(String firstName, String lastName, String title, String email, BigDecimal ownershipPercent,
                     boolean significantControl, String dateOfBirth, Integer birthYear, Address address) {
    }

    @Schema(name = "PlanSelection")
    record PlanView(String planCode, String planName, String cadence, String paymentMethodKind, String cardBrand,
                    String cardLast4, Integer cardExpMonth, Integer cardExpYear, FirstCharge firstCharge) {
    }

    @Schema(name = "TimelineEntry")
    record TimelineView(String action, String fromStatus, String toStatus, String actorId, String reasonCode,
                        String note, List<String> items, Instant occurredAt) {

        static TimelineView of(TimelineEntry t) {
            return new TimelineView(t.action().name(), t.fromStatus() == null ? null : t.fromStatus().name(),
                    t.toStatus().name(), t.actorId(), t.reasonCode(), t.note(), t.items(), t.occurredAt());
        }
    }

    @Schema(name = "DocumentAccess")
    record AccessView(String assetId, String kind, String viewerId, Instant accessedAt) {

        static AccessView of(DocumentAccess a) {
            return new AccessView(a.assetId(), a.kind(), a.viewerId(), a.accessedAt());
        }
    }

    @Schema(name = "Application")
    record ApplicationResponse(String id, String reference, String orgId, String kind, String status,
                               String currentStep, List<String> steps, List<String> completedSteps,
                               List<String> prefilledFields, BusinessSection business,
                               BusinessDetailsView businessDetails, List<OwnerView> owners,
                               StripeVerification stripe, PayoutSection payouts, PlanView plan,
                               ComplianceSection compliance, List<DocumentRef> documents,
                               List<String> requestedItems, List<String> failedChecks, String reviewerId,
                               String finalApproverId, String reasonCode, int submissionCount, Instant submittedAt,
                               List<TimelineView> timeline, List<AccessView> accessLog, Instant createdAt,
                               Instant updatedAt) {

        static ApplicationResponse of(ApplicationView v) {
            Application a = v.application();
            ApplicationData d = a.data();
            boolean mask = v.pii() == PiiLevel.MASKED;
            BusinessDetailsSection bd = d.businessDetails();
            BusinessDetailsView details = bd == null ? null : new BusinessDetailsView(bd.legalFirstName(),
                    bd.legalLastName(), bd.legalName(), bd.dba(),
                    bd.taxIdType() == null ? null : bd.taxIdType().name(), v.taxIdMasked(), v.taxIdFull(),
                    dob(bd.dateOfBirth(), mask), year(bd.dateOfBirth()), bd.stateOfFormation(),
                    bd.dateOfFormation(), bd.address(), bd.formationDocumentAssetId(),
                    bd.irsDeterminationLetterAssetId());
            List<OwnerView> owners = d.owners() == null ? null : d.owners().stream().map(o -> new OwnerView(
                    o.firstName(), o.lastName(), o.title(), o.email(), o.ownershipPercent(), o.significantControl(),
                    dob(o.dateOfBirth(), mask), year(o.dateOfBirth()), o.address())).toList();
            PlanSection p = d.plan();
            PlanView plan = p == null ? null : new PlanView(p.planCode(), v.planName(),
                    p.cadence() == null ? null : p.cadence().name(),
                    p.paymentMethodKind() == null ? null : p.paymentMethodKind().name(), p.cardBrand(), p.cardLast4(),
                    p.cardExpMonth(), p.cardExpYear(), v.firstCharge());
            return new ApplicationResponse(a.id(), a.reference(), a.orgId(), a.kind().name(), a.status().name(),
                    a.currentStep().key(), v.steps().stream().map(OnboardingStep::key).toList(),
                    v.completedSteps().stream().map(OnboardingStep::key).toList(),
                    a.prefilledFields().stream().sorted().toList(), d.business(), details, owners, d.stripe(),
                    d.payouts(), plan, d.compliance(), v.documents(), a.requestedItems(), a.failedChecks(),
                    a.reviewerId(), a.finalApproverId(), a.reasonCode(), a.submissionCount(), a.submittedAt(),
                    v.timeline().stream().map(TimelineView::of).toList(),
                    v.accessLog().stream().map(AccessView::of).toList(), a.createdAt(), a.updatedAt());
        }

        private static String dob(LocalDate d, boolean mask) {
            if (d == null) {
                return null;
            }
            return mask ? d.getYear() + "-••-••" : d.toString();
        }

        private static Integer year(LocalDate d) {
            return d == null ? null : d.getYear();
        }
    }

    @Schema(name = "AccountSession", description = "client_secret for Stripe's embedded Connect components. localTest "
            + "is true only on the local profile without a Stripe key (LocalTestStripePolicy): there is no Stripe to "
            + "load and the client shows its labelled local verification instead.")
    record AccountSessionResponse(String accountId, String clientSecret, Instant expiresAt, boolean localTest) {
    }
}
