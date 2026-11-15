package com.afrotransact.platform.test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.Architectures;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        onlyPublishedApiOfOtherModules(basePackage).check(classes);
        otherModulesApiOnlyInModuleAdapters(basePackage).check(classes);
        controllerAdvicesAreScoped(basePackage).check(classes);
    }

    private static DescribedPredicate<JavaClass> internalsOfOtherModules(String base) {
        return JavaClass.Predicates.resideInAPackage("com.afrotransact..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(base + "..", "com.afrotransact.platform..",
                        "com.afrotransact.module..", "com.afrotransact.*.api.."))
                .as("classes of another module that are not its published api package");
    }

    /**
     * Release 1 (ADR 0011): a module may depend on another module only through that module's published api package
     * ({@code com.afrotransact.<module>.api}), never its domain, application, adapters or repositories. Trivially true for a
     * service that calls no other module in-process; it keeps being true when someone adds one.
     */
    public static ArchRule onlyPublishedApiOfOtherModules(String base) {
        return ArchRuleDefinition.noClasses().that().resideInAPackage(base + "..")
                .should().dependOnClassesThat(internalsOfOtherModules(base))
                .because("modules talk to each other through the published api package only");
    }

    /** The published api of ANOTHER module is used by the module's in-process adapters and its configuration, nowhere else. */
    public static ArchRule otherModulesApiOnlyInModuleAdapters(String base) {
        return ArchRuleDefinition.noClasses().that().resideInAPackage(base + "..")
                .and().resideOutsideOfPackages(base + "..adapter.out.module..", base + "..config..")
                .should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.afrotransact.*.api..")
                        .and(JavaClass.Predicates.resideOutsideOfPackage(base + ".api..")))
                .because("the domain and the use cases call ports; only an adapter or the wiring knows another module");
    }

    /**
     * A {@code @RestControllerAdvice} without {@code basePackages} handles the exceptions of every controller in the JVM; inside
     * afrotransact-core that would change another module's error responses.
     */
    public static ArchRule controllerAdvicesAreScoped(String base) {
        ArchCondition<JavaClass> scoped = new ArchCondition<>("declare basePackages or basePackageClasses") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                RestControllerAdvice advice = item.getAnnotationOfType(RestControllerAdvice.class);
                boolean ok = advice.basePackages().length > 0 || advice.basePackageClasses().length > 0
                        || advice.value().length > 0;
                events.add(new SimpleConditionEvent(item, ok, item.getName()
                        + (ok ? " is scoped" : " is a global @RestControllerAdvice: add basePackages")));
            }
        };
        return ArchRuleDefinition.classes().that().resideInAPackage(base + "..")
                .and().areAnnotatedWith(RestControllerAdvice.class).should(scoped).allowEmptyShould(true);
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
