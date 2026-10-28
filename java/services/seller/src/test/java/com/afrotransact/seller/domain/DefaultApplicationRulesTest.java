package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.seller.adapter.out.phone.LibPhoneNumbers;
import org.junit.jupiter.api.Test;

class DefaultApplicationRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final RuleContext SELLER = new RuleContext(OrgKind.SELLER, Set.of("US"), TODAY);
    private static final RuleContext PROVIDER = new RuleContext(OrgKind.PROVIDER, Set.of("US"), TODAY);
    private static final Address ADDRESS = new Address("9800 Westheimer Rd", null, "Houston", "TX", "77042", "US");

    private final DefaultApplicationRules rules = new DefaultApplicationRules(new LibPhoneNumbers());

    @Test
    void nonLiveCountryIsRejectedWithADedicatedCode() {
        BusinessSection ca = new BusinessSection(Offering.PRODUCTS, null, "CA", null, null, null, null);
        assertThatThrownBy(() -> rules.business(ca, SELLER, false))
                .isInstanceOfSatisfying(CountryNotLiveException.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.country-not-live");
                    assertThat(e.country()).isEqualTo("CA");
                });
    }

    @Test
    void brandingForThePlainPageIsCheckedAndNormalised() {
        BusinessSection.Branding good = new BusinessSection.Branding("  Home-cooked Nigerian meals  ", "#1A7F4B",
                "https://cdn.example.com/logo.png");
        Validated<BusinessSection> ok = rules.business(new BusinessSection(Offering.PRODUCTS, null, "US", "Ade's Kitchen",
                null, null, null, good), SELLER, false);
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.value().branding()).isEqualTo(new BusinessSection.Branding("Home-cooked Nigerian meals",
                "#1a7f4b", "https://cdn.example.com/logo.png"));

        Validated<BusinessSection> bad = rules.business(new BusinessSection(Offering.PRODUCTS, null, "US", null, null,
                null, null, new BusinessSection.Branding("x".repeat(501), "green",
                "http://insecure.example/logo.png")), SELLER, false);
        assertThat(bad.errors()).containsKeys("business.branding.description", "business.branding.brandColor",
                "business.branding.logoUrl");
    }

    @Test
    void phoneIsNormalisedToE164AndMustBeAValidNumber() {
        Validated<BusinessSection> ok = rules.business(new BusinessSection(Offering.PRODUCTS,
                EntityType.SOLE_PROPRIETOR, "us", "Ade's Kitchen", "Ade@Example.com", "(713) 249-0142", null),
                SELLER, true);
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.value().phone()).isEqualTo("+17132490142");
        assertThat(ok.value().country()).isEqualTo("US");
        assertThat(ok.value().contactEmail()).isEqualTo("ade@example.com");

        Validated<BusinessSection> bad = rules.business(new BusinessSection(null, null, null, null, null, "12345",
                null), SELLER, false);
        assertThat(bad.errors()).containsKey("business.phone").doesNotContainKey("business.offering");
    }

    @Test
    void sellersCantPickServicesOnlyAndProvidersCantPickProductsOnly() {
        assertThat(rules.business(new BusinessSection(Offering.SERVICES, null, null, null, null, null, null), SELLER,
                false).errors()).containsKey("business.offering");
        assertThat(rules.business(new BusinessSection(Offering.PRODUCTS, null, null, null, null, null, null),
                PROVIDER, false).errors()).containsKey("business.offering");
        assertThat(rules.business(new BusinessSection(Offering.BOTH, null, null, null, null, null, null), PROVIDER,
                false).errors()).isEmpty();
    }

    @Test
    void partialSaveValidatesOnlyWhatIsPresent() {
        BusinessDetailsInput partial = new BusinessDetailsInput("Ade", null, null, null, null, null, null, null, null,
                null, null);
        assertThat(rules.businessDetails(partial, EntityType.SOLE_PROPRIETOR, false, SELLER, false).errors())
                .isEmpty();
        assertThat(rules.businessDetails(partial, EntityType.SOLE_PROPRIETOR, false, SELLER, true).errors())
                .containsKeys("businessDetails.legalLastName", "businessDetails.taxId",
                        "businessDetails.dateOfBirth", "businessDetails.address");
    }

    @Test
    void soleProprietorNeedsNameSsnAdultDobAndAddress() {
        BusinessDetailsInput minor = new BusinessDetailsInput("Ade", "Ade", null, null, "123-45-6789",
                TODAY.minusYears(17), null, null, ADDRESS, null, null);
        assertThat(rules.businessDetails(minor, EntityType.SOLE_PROPRIETOR, false, SELLER, true).errors())
                .containsOnlyKeys("businessDetails.dateOfBirth");
        BusinessDetailsInput adult = new BusinessDetailsInput("Ade", "Ade", null, null, "123456789",
                TODAY.minusYears(18), null, null, ADDRESS, null, null);
        assertThat(rules.businessDetails(adult, EntityType.SOLE_PROPRIETOR, false, SELLER, true).errors()).isEmpty();
        assertThat(rules.taxId(EntityType.SOLE_PROPRIETOR, "12-3456789").value().formatted()).isEqualTo("123-45-6789");
        assertThat(rules.taxId(EntityType.SOLE_PROPRIETOR, "000-00-0000").errors()).containsKey("businessDetails"
                + ".taxId");
    }

    @Test
    void companiesNeedLegalNameEinFormationAndDocument() {
        BusinessDetailsInput llc = new BusinessDetailsInput(null, null, null, null, "123-45-6789", null, null, null,
                ADDRESS, null, null);
        assertThat(rules.businessDetails(llc, EntityType.MULTI_MEMBER_LLC, false, SELLER, true).errors())
                .containsKeys("businessDetails.legalName", "businessDetails.stateOfFormation",
                        "businessDetails.dateOfFormation", "businessDetails.formationDocumentAssetId")
                .doesNotContainKey("businessDetails.dateOfBirth");
        assertThat(rules.taxId(EntityType.CORPORATION, "123456789").value())
                .isEqualTo(new TaxIdCheck(TaxIdType.EIN, "12-3456789"));
        BusinessDetailsInput future = new BusinessDetailsInput(null, null, "Ade's Kitchen LLC", null, null, null,
                "XX", TODAY.plusDays(1), ADDRESS, "asset-1", null);
        assertThat(rules.businessDetails(future, EntityType.SINGLE_MEMBER_LLC, true, SELLER, true).errors())
                .containsOnlyKeys("businessDetails.stateOfFormation", "businessDetails.dateOfFormation");
    }

    @Test
    void nonprofitsAlsoNeedTheIrsDeterminationLetter() {
        BusinessDetailsInput np = new BusinessDetailsInput(null, null, "Diaspora Aid", null, "12-3456789", null, "GA",
                TODAY.minusYears(3), ADDRESS, "asset-1", null);
        assertThat(rules.businessDetails(np, EntityType.NONPROFIT, false, SELLER, true).errors())
                .containsOnlyKeys("businessDetails.irsDeterminationLetterAssetId");
        assertThat(rules.businessDetails(np, EntityType.CORPORATION, false, SELLER, true).errors()).isEmpty();
    }

    @Test
    void ownersRules() {
        Owner sixty = owner("Adewale", 60, true);
        Owner forty = owner("Funmi", 40, false);
        assertThat(rules.owners(List.of(sixty, forty), EntityType.MULTI_MEMBER_LLC, SELLER, true).errors()).isEmpty();
        assertThat(rules.owners(List.of(owner("A", 70, true), owner("B", 40, false)), EntityType.CORPORATION,
                SELLER, false).errors()).containsKey("owners.ownershipTotal");
        assertThat(rules.owners(List.of(owner("Ann", 60, false), owner("Bob", 40, false)), EntityType.CORPORATION,
                SELLER, true).errors()).containsKey("owners.significantControl");
        assertThat(rules.owners(List.of(sixty), EntityType.PARTNERSHIP, SELLER, true).errors())
                .containsKey("owners.owners");
        assertThat(rules.owners(List.of(sixty), EntityType.SINGLE_MEMBER_LLC, SELLER, true).errors()).isEmpty();
        assertThat(rules.owners(List.of(sixty, owner("Cee", 10, false)), EntityType.CORPORATION, SELLER, true)
                .errors()).containsKey("owners[1].ownershipPercent");
        assertThat(rules.owners(List.of(sixty, owner("Cee", 10, true)), EntityType.CORPORATION, SELLER, true)
                .errors()).as("a CEO with significant control may own < 25%").isEmpty();
        assertThat(rules.owners(List.of(sixty), EntityType.SOLE_PROPRIETOR, SELLER, false).errors())
                .containsKey("owners.owners");
    }

    @Test
    void planNeedsMatchingKindAndAValidCardForPaidPlans() {
        Plan growth = Plan.standard("seller_growth", OrgKind.SELLER, "Seller Growth", null, 3900, 30, List.of(), 1);
        PlanSection expired = new PlanSection("seller_growth", Cadence.QUARTERLY, PaymentMethodKind.CARD, "pm_1",
                "visa", "4242", 9, 2026);
        assertThat(rules.plan(expired, Optional.of(growth), ApplicationData.empty(), SELLER, true).errors())
                .containsOnlyKeys("plan.cardExpYear");
        PlanSection ok = new PlanSection("seller_growth", Cadence.QUARTERLY, PaymentMethodKind.CARD, "pm_1", "visa",
                "4242", 10, 2026);
        assertThat(rules.plan(ok, Optional.of(growth), ApplicationData.empty(), SELLER, true).errors()).isEmpty();
        assertThat(rules.plan(ok, Optional.of(growth), ApplicationData.empty(), PROVIDER, true).errors())
                .containsKey("plan.planCode");
        PlanSection noPm = new PlanSection("seller_growth", Cadence.MONTHLY, null, null, null, null, null, null);
        assertThat(rules.plan(noPm, Optional.of(growth), ApplicationData.empty(), SELLER, true).errors())
                .containsKeys("plan.paymentMethodKind", "plan.stripePaymentMethodId");
    }

    @Test
    void complianceRequiresPermitsLicencesAgreementsAndW9() {
        ComplianceSection.ServiceLicence expired = new ComplianceSection.ServiceLicence("Braiding", true, "C-123",
                "GA", TODAY.minusDays(1), "asset-lic");
        assertThat(rules.compliance(new ComplianceSection(List.of(), null, null, List.of(expired), null, null, null,
                null), Offering.SERVICES, PROVIDER, false).errors()).containsOnlyKeys("compliance.services[0]"
                + ".expiresOn");

        ComplianceSection.ServiceLicence noDoc = new ComplianceSection.ServiceLicence("Braiding", true, "C-123", "GA",
                TODAY.plusYears(1), null);
        assertThat(rules.compliance(new ComplianceSection(List.of(), null, null, List.of(noDoc), null, true, true,
                "Ama"), Offering.SERVICES, PROVIDER, true).errors())
                .containsOnlyKeys("compliance.services[0].documentAssetId", "compliance.insuranceDocumentAssetId");

        ComplianceSection food = new ComplianceSection(List.of("Groceries", "beauty"), null, false, List.of(), null,
                false, null, null);
        assertThat(rules.compliance(food, Offering.PRODUCTS, SELLER, true).errors()).containsOnlyKeys(
                "compliance.foodPermit", "compliance.beautyLabellingConfirmed", "compliance.agreementsAccepted",
                "compliance.w9Certified");
        ComplianceSection complete = new ComplianceSection(List.of("groceries"), new ComplianceSection.FoodPermit(
                "FE-1", "Harris County", TODAY.plusYears(1), "asset-permit", "COTTAGE_FOOD"), null, List.of(), null, true, true, "Ade");
        assertThat(rules.compliance(complete, Offering.PRODUCTS, SELLER, true).errors()).isEmpty();
    }

    @Test
    void ownersNeedTitleEmailBirthDateAndHomeAddressOnlyWhenComplete() {
        Owner bare = new Owner("Kofi", "Mensah", null, null, BigDecimal.valueOf(100), true, null, null);
        assertThat(rules.owners(List.of(bare), EntityType.CORPORATION, SELLER, false).errors()).isEmpty();
        assertThat(rules.owners(List.of(bare), EntityType.CORPORATION, SELLER, true).errors()).containsOnlyKeys(
                "owners[0].title", "owners[0].email", "owners[0].dateOfBirth", "owners[0].address");
        Owner badAddress = new Owner("Kofi", "Mensah", "CEO", "kofi@example.com", BigDecimal.valueOf(100), true,
                LocalDate.of(1980, 2, 2), new Address("1 Main St", null, "Houston", "Texas", "7704", "US"));
        assertThat(rules.owners(List.of(badAddress), EntityType.CORPORATION, SELLER, false).errors())
                .containsOnlyKeys("owners[0].address.state", "owners[0].address.postalCode");
    }

    @Test
    void payoutsAreCompleteOnlyOnceStripeHoldsAUsableBankAccountAndPayoutsCanRun() {
        Instant now = Instant.parse("2026-10-10T12:00:00Z");
        StripeVerification verified = new StripeVerification("acct_1", true, true, true, "verified", "verified",
                List.of(), null, now);
        PayoutSection bank = PayoutSection.fromStripe(new PayoutBank("ba_1", "STRIPE TEST BANK", "6789", "new"));
        assertThat(DefaultApplicationRules.payoutErrors(null, null)).as("no Stripe account yet")
                .containsOnlyKeys("payouts.externalAccount");
        assertThat(DefaultApplicationRules.payoutErrors(null, verified)).as("Stripe holds no bank yet")
                .containsOnlyKeys("payouts.externalAccount");
        assertThat(DefaultApplicationRules.payoutErrors(new PayoutSection("Adaeze Okafor", null, null, null, null,
                false), verified)).as("a prefilled holder name is not a payout account")
                .containsOnlyKeys("payouts.externalAccount");
        assertThat(DefaultApplicationRules.payoutErrors(bank, verified)).isEmpty();
        PayoutSection failed = PayoutSection.fromStripe(new PayoutBank("ba_1", "STRIPE TEST BANK", "6789",
                "errored"));
        assertThat(failed.externalAccountLinked()).isFalse();
        assertThat(DefaultApplicationRules.payoutErrors(failed, verified)).containsOnlyKeys("payouts.externalAccount");
        StripeVerification bankDue = new StripeVerification("acct_1", true, true, false, "verified", "verified",
                List.of("external_account"), "requirements.past_due", now);
        assertThat(DefaultApplicationRules.payoutErrors(bank, bankDue)).containsOnlyKeys("payouts.externalAccount");
        StripeVerification checking = new StripeVerification("acct_1", true, true, false, "pending", "pending",
                List.of(), "requirements.pending_verification", now);
        assertThat(DefaultApplicationRules.payoutErrors(bank, checking))
                .as("payouts only waiting for Stripe's checks (and our manual schedule) are fine").isEmpty();
        StripeVerification disabled = new StripeVerification("acct_1", true, true, false, "verified", "verified",
                List.of(), "under_review", now);
        assertThat(DefaultApplicationRules.payoutErrors(bank, disabled)).containsOnlyKeys("payouts.payoutsEnabled");

        ApplicationData linked = ApplicationData.empty().withStripe(verified).withPayouts(bank);
        assertThat(rules.completedSteps(linked, Optional.empty(), SELLER)).contains(OnboardingStep.PAYOUTS);
        ApplicationData typedNotLinked = ApplicationData.empty().withStripe(verified).withPayouts(
                new PayoutSection("Adaeze Okafor", "checking", "Chase", "0021", "6789", false));
        assertThat(rules.completedSteps(typedNotLinked, Optional.empty(), SELLER))
                .doesNotContain(OnboardingStep.PAYOUTS);
        assertThat(rules.submission(ApplicationData.empty(), Optional.empty(), SELLER))
                .containsKey("payouts.externalAccount");
    }

    @Test
    void stripeVerificationIsCompleteOnlyWhenStripeHasNothingDue() {
        Instant now = Instant.parse("2026-10-10T12:00:00Z");
        assertThat(DefaultApplicationRules.stripeErrors(null)).containsOnlyKeys("stripe.account");
        assertThat(DefaultApplicationRules.stripeErrors(StripeVerification.created("acct_1", now)))
                .containsOnlyKeys("stripe.detailsSubmitted");
        StripeVerification due = new StripeVerification("acct_1", true, false, false, "pending", "pending",
                List.of("individual.verification.document"), null, now);
        assertThat(DefaultApplicationRules.stripeErrors(due)).containsOnlyKeys("stripe.currentlyDue");
        StripeVerification rejected = new StripeVerification("acct_1", true, false, false, "unverified", "failed",
                List.of(), "rejected.fraud", now);
        assertThat(DefaultApplicationRules.stripeErrors(rejected)).containsOnlyKeys("stripe.account");
        StripeVerification pending = new StripeVerification("acct_1", true, true, false, "pending", "pending",
                List.of(), null, now);
        assertThat(DefaultApplicationRules.stripeErrors(pending)).as("still verifying is not the applicant's job")
                .isEmpty();
    }

    @Test
    void foodPermitNeedsAKnownType() {
        ComplianceSection.FoodPermit noType = new ComplianceSection.FoodPermit("FE-1", null, TODAY.plusYears(1),
                "asset-permit", null);
        ComplianceSection c = new ComplianceSection(List.of("prepared_food"), noType, null, List.of(), null, true,
                true, "Ade");
        assertThat(rules.compliance(c, Offering.PRODUCTS, SELLER, true).errors())
                .containsOnlyKeys("compliance.foodPermit.permitType");
        ComplianceSection.FoodPermit odd = new ComplianceSection.FoodPermit("FE-1", null, TODAY.plusYears(1),
                "asset-permit", "LIQUOR");
        assertThat(rules.compliance(new ComplianceSection(List.of("groceries"), odd, null, List.of(), null, null, null,
                null), Offering.PRODUCTS, SELLER, false).errors()).containsOnlyKeys("compliance.foodPermit.permitType");
    }

    private static Owner owner(String name, int pct, boolean control) {
        return new Owner(name, "Ade", "Owner", name.toLowerCase() + "@example.com", BigDecimal.valueOf(pct), control,
                LocalDate.of(1981, 1, 1), ADDRESS);
    }
}
