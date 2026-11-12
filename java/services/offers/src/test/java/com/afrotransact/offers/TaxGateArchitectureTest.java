package com.afrotransact.offers;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferRequiresTax;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when a new code path can make an offer live without the seller's own RESOLVED tax decision
 * (docs/engineering/tax-gate-paths.md, S384). An offer leaves TAX_HELD for ACTIVE in exactly one component, and no offer is
 * stored ACTIVE without the basis that says why.
 */
class TaxGateArchitectureTest {

    private static final String RELEASE = "com.afrotransact.offers.application.TaxHoldServiceImpl";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.afrotransact.offers");

    private static DescribedPredicate<JavaCall<?>> callsToOffer(String namePattern) {
        return JavaCall.Predicates.target(HasName.Predicates.nameMatching(namePattern))
                .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(JavaClass.Predicates.equivalentTo(Offer.class))));
    }

    @Test
    void onlyTheTaxReleaseComponentMovesAnOfferFromTaxHeldToActive() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName(RELEASE)
                .and().areNotAssignableTo(Offer.class)
                .should().callMethodWhere(callsToOffer("releasedFromTaxHold|withTaxBasis|heldForTax"))
                .because("an offer is live only when tax-returns holds the seller's RESOLVED decision, and one component "
                        + "asks it");
        rule.check(CLASSES);
    }

    @Test
    void theRulesStillSeeTheReleaseComponentCallingTheTransitions() {
        // Guards the guard: if the methods are renamed, the rule above would silently match nothing.
        ArchRule rule = ArchRuleDefinition.classes().that().haveFullyQualifiedName(RELEASE)
                .should().callMethodWhere(callsToOffer("releasedFromTaxHold"))
                .andShould().callMethodWhere(callsToOffer("withTaxBasis"))
                .andShould().callMethodWhere(callsToOffer("heldForTax"));
        rule.check(CLASSES);
    }

    @Test
    void everyOfferInsertInThePersistenceAdapterChecksTheTaxBasis() {
        ArchRule rule = ArchRuleDefinition.classes().that().haveSimpleName("JdbcOfferRepository")
                .should().callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("require"))
                        .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(
                                JavaClass.Predicates.equivalentTo(OfferRequiresTax.class)))));
        rule.check(CLASSES);
    }

    @Test
    void theOfferServiceAsksTheTaxGateBeforeItStoresAnOffer() {
        ArchRule rule = ArchRuleDefinition.classes().that().haveSimpleName("OfferServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("OfferTaxGate");
        rule.check(CLASSES);
    }

    @Test
    void theBulkBatchPathsNeverStoreAnOfferOrMoveItIntoActive() {
        // S393/S394: a bulk row creates an offer only through OfferService.create (which asks the tax gate) and changes
        // terms through revise/update, which keep the status. The batch service never inserts, releases or marks a basis.
        ArchRule rule = ArchRuleDefinition.noClasses().that().haveSimpleName("OfferBatchServiceImpl")
                .should().callMethodWhere(callsToOffer("releasedFromTaxHold|withTaxBasis|heldForTax"))
                .orShould().callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("insert"))
                        .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(
                                JavaClass.Predicates.simpleName("OfferRepository")))))
                .because("only OfferServiceImpl stores new offers after the tax gate, and only TaxHoldServiceImpl releases them");
        rule.check(CLASSES);
    }

    @Test
    void theBulkBatchServiceDependsOnTheOfferServiceForCreation() {
        ArchRule rule = ArchRuleDefinition.classes().that().haveSimpleName("OfferBatchServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("OfferService");
        rule.check(CLASSES);
    }
}
