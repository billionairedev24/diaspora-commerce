package com.afrotransact.platform.test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.Architectures;

/**
 * Layering rules every service asserts in its {@code ArchitectureTest}:
 * domain → application → port → adapter, domain free of frameworks, use cases behind interfaces.
 */
public final class HexagonalArchitectureRules {

    private HexagonalArchitectureRules() {
    }

    /** Verifies all rules for the service rooted at {@code basePackage} (e.g. {@code com.afrotransact.catalog}). */
    public static void verify(JavaClasses classes, String basePackage) {
        layers(basePackage).check(classes);
        domainIsFrameworkFree(basePackage).check(classes);
        implementationsArePackagePrivate(basePackage).check(classes);
        applicationServicesAreInterfaces(basePackage).check(classes);
    }

    public static ArchRule layers(String base) {
        return Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(base + "..")
                .withOptionalLayers(true)
                .layer("Domain").definedBy(base + ".domain..")
                .layer("Application").definedBy(base + ".application..")
                .layer("Port").definedBy(base + ".port..")
                .layer("Adapter").definedBy(base + ".adapter..")
                .layer("Config").definedBy(base + ".config..")
                .whereLayer("Adapter").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter", "Config")
                .whereLayer("Port").mayOnlyBeAccessedByLayers("Application", "Adapter", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Port", "Adapter", "Config");
    }

    public static ArchRule domainIsFrameworkFree(String base) {
        return ArchRuleDefinition.noClasses().that().resideInAPackage(base + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..")
                .because("the domain model must not depend on frameworks");
    }

    public static ArchRule implementationsArePackagePrivate(String base) {
        return ArchRuleDefinition.classes().that().resideInAPackage(base + ".application..")
                .and().haveSimpleNameEndingWith("Impl")
                .should().notBePublic()
                .because("callers depend on the use-case interface, never the implementation");
    }

    public static ArchRule applicationServicesAreInterfaces(String base) {
        return ArchRuleDefinition.classes().that().resideInAPackage(base + ".application..")
                .and().haveSimpleNameEndingWith("Service")
                .should().beInterfaces()
                .allowEmptyShould(true)
                .because("each use case is declared as an interface with an Impl class");
    }
}
