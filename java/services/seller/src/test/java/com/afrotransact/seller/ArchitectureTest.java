package com.afrotransact.seller;

import com.afrotransact.platform.test.HexagonalArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void followsHexagonalLayering() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.afrotransact.seller");
        HexagonalArchitectureRules.verify(classes, "com.afrotransact.seller");
    }
}
