package com.afrotransact.orders;

import com.afrotransact.platform.test.HexagonalArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final String BASE = "com.afrotransact.orders";

    @Test
    void followsHexagonalLayering() {
        HexagonalArchitectureRules.verify(classes(), BASE);
    }

    @Test
    void temporalStaysOutOfTheDomain() {
        ArchRuleDefinition.noClasses().that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("io.temporal..", "io.grpc..")
                .because("workflows orchestrate the domain; the domain never knows about Temporal")
                .check(classes());
    }

    private static JavaClasses classes() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }
}
