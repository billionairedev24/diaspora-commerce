package com.afrotransact.platform.events;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The shared library must stay independent of every service and keep field injection out. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.afrotransact.platform.events");

    @Test
    void libraryDoesNotDependOnServices() {
        noClasses().that().resideInAPackage("com.afrotransact.platform.events..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.afrotransact.payments..", "com.afrotransact.orders..", "com.afrotransact.catalog..",
                        "com.afrotransact.identity..", "com.afrotransact.offers..", "com.afrotransact.seller..")
                .check(CLASSES);
    }

    @Test
    void consumerPackageDoesNotUseFieldInjection() {
        noClasses().that().resideInAPackage("com.afrotransact.platform.events.consumer..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.beans.factory.annotation.Autowired")
                .check(CLASSES);
    }
}
