package com.afrotransact.catalog.domain.imports;

import com.afrotransact.catalog.domain.sizing.MeasurementUnit;

/**
 * What a size chart upload is validated against.
 *
 * @param productTypeCode the product type the charts are made for (the template used); null = any
 * @param defaultUnit     the unit of a chart that names none
 */
public record ChartImportContext(String productTypeCode, MeasurementUnit defaultUnit) {

    public ChartImportContext {
        defaultUnit = defaultUnit == null ? MeasurementUnit.CM : defaultUnit;
    }
}
