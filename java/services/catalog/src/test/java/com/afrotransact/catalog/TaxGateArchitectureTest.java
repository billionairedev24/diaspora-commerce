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
}
