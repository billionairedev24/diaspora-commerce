package com.afrotransact.catalog;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when a new code path can put a product on sale without the tax gate (docs/engineering/tax-gate-paths.md).
 * Every transition into LIVE is a method of {@link Product}; only the gate component may call them. The persistence
 * adapter also refuses to store a LIVE product that fails {@link LiveRequiresTax}, which these rules keep in place.
 */
class TaxGateArchitectureTest {

    private static final String GATE = "com.afrotransact.catalog.application.ProductLiveGateImpl";
    private static final String LIVE_TRANSITIONS = "approve|resume|restoreFromTakedown|restoreAfterTaxHold";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.afrotransact.catalog");

    private static DescribedPredicate<JavaCall<?>> callsToProduct(String namePattern) {
        return JavaCall.Predicates.target(HasName.Predicates.nameMatching(namePattern))
                .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(JavaClass.Predicates.equivalentTo(Product.class))));
    }

    @Test
    void onlyTheLiveGateMayPutAProductOnSale() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName(GATE)
                .and().areNotAssignableTo(Product.class)
                .should().callMethodWhere(callsToProduct(LIVE_TRANSITIONS))
                .because("every transition into LIVE must go through ProductLiveGate so a variant without a resolved tax "
                        + "decision is never purchasable");
        rule.check(CLASSES);
    }

    @Test
    void theRulesStillSeeTheGateCallingTheTransitions() {
        // Guards the guard: if the transition methods are renamed, the rule above would silently match nothing.
        ArchRule rule = ArchRuleDefinition.classes().that().haveFullyQualifiedName(GATE)
                .should().callMethodWhere(callsToProduct("approve"))
                .andShould().callMethodWhere(callsToProduct("resume"))
                .andShould().callMethodWhere(callsToProduct("restoreFromTakedown"))
                .andShould().callMethodWhere(callsToProduct("restoreAfterTaxHold"));
        rule.check(CLASSES);
    }

    @Test
    void everyProductWriteInThePersistenceAdapterChecksTheLiveInvariant() {
        ArchRule rule = ArchRuleDefinition.classes().that().haveSimpleName("JdbcProductRepository")
                .should().callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("require"))
                        .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(
                                JavaClass.Predicates.equivalentTo(LiveRequiresTax.class)))));
        rule.check(CLASSES);
    }

    @Test
    void nothingInTheApplicationLayerBuildsAProductWithAStatusByHandExceptTheWriterOfNewProducts() {
        // Product's canonical constructor takes a status. New products are built by the writer (IN_REVIEW or
        // PENDING_CATEGORY_APPROVAL); everything else changes status through Product methods and the gate.
        DescribedPredicate<JavaConstructorCall> withStatus = DescribedPredicate.describe(
                "a Product constructor with a status argument",
                call -> call.getTargetOwner().isEquivalentTo(Product.class) && call.getTarget().getRawParameterTypes()
                        .stream().anyMatch(t -> t.isEquivalentTo(ProductStatus.class)));
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..application..")
                .and().doNotHaveSimpleName("ProductWriterImpl")
                .should().callConstructorWhere(withStatus)
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }

    @Test
    void theChangeRequestApplierNeverChangesAPagesStatus() {
        // S385: an approved edit changes content, never whether the page is on sale (a LIVE page stays as it is).
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().haveSimpleName("ChangeRequestApplierImpl")
                .should().callMethodWhere(callsToProduct(
                        "approve|resume|restoreFromTakedown|restoreAfterTaxHold|holdForTax|reject|pause|takeDown"
                                + "|categoryApproved|exemptionApproved|exemptionRejected|mergedAway"))
                .because("an edit that touches a live page must not move it in or out of LIVE; tax-relevant changes go "
                        + "through ProductLiveGate");
        rule.check(CLASSES);
    }

    @Test
    void mergesExemptionDecisionsAndVariantMovesNeverPutAPageOnSale() {
        // S385-S387: merging, approving a no-barcode exemption, adding, moving or splitting a variant only ever lead to
        // review (a split page) or leave sale through the gate, never to LIVE.
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().haveSimpleNameStartingWith("PageMergerImpl")
                .or().haveSimpleNameStartingWith("GtinExemptionServiceImpl")
                .or().haveSimpleNameStartingWith("ReviewQueueServiceImpl")
                .or().haveSimpleNameStartingWith("PageVariantServiceImpl")
                .or().haveSimpleNameStartingWith("ChangeRequestServiceImpl")
                .should().callMethodWhere(callsToProduct(LIVE_TRANSITIONS))
                .because("only ProductLiveGate may put a product on sale");
        rule.check(CLASSES);
    }

    @Test
    void theBulkImporterCannotCallProductApproveOrAnyOtherLiveTransition() {
        // S393: an upload row never puts a page or an offer on sale. Pages go through ProductWriter (in review, never LIVE)
        // and the gate; offers are created by offers-service, which asks the tax gate. The importer has no path to LIVE.
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().haveSimpleNameStartingWith("ImportJob")
                .or().haveSimpleNameStartingWith("OfferImportJob")
                .or().haveSimpleNameStartingWith("ProductImportService")
                .or().haveSimpleNameStartingWith("ImportMatcher")
                .or().resideInAPackage("..domain.imports..")
                .should().callMethodWhere(callsToProduct(
                        LIVE_TRANSITIONS + "|holdForTax|reject|pause|takeDown|categoryApproved|exemptionApproved"))
                .because("importing rows must never change whether a page is on sale: only ProductLiveGate does");
        rule.check(CLASSES);
    }

    @Test
    void theBulkImporterNeverBuildsAProductWithAStatusAndOnlyTheOfferJobTalksToOffers() {
        DescribedPredicate<JavaConstructorCall> withStatus = DescribedPredicate.describe(
                "a Product constructor with a status argument",
                call -> call.getTargetOwner().isEquivalentTo(Product.class) && call.getTarget().getRawParameterTypes()
                        .stream().anyMatch(t -> t.isEquivalentTo(ProductStatus.class)));
        ArchRule noStatus = ArchRuleDefinition.noClasses()
                .that().haveSimpleNameStartingWith("ImportJob")
                .or().haveSimpleNameStartingWith("OfferImportJob")
                .or().haveSimpleNameStartingWith("ProductImportService")
                .or().resideInAPackage("..domain.imports..")
                .should().callConstructorWhere(withStatus).allowEmptyShould(true);
        noStatus.check(CLASSES);
        // The offer writer is the only way a bulk row reaches an offer: offers-service applies the selling approval and
        // the tax rule. Nothing else in catalog creates or changes offers.
        ArchRule onlyTheJob = ArchRuleDefinition.noClasses()
                .that().doNotHaveSimpleName("OfferRowProcessorImpl")
                .and().doNotHaveSimpleName("HttpOfferWriter")
                .and().resideOutsideOfPackage("..port..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("OfferWriter")
                .allowEmptyShould(true);
        onlyTheJob.check(CLASSES);
    }
}
