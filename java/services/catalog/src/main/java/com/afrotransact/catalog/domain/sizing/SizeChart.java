package com.afrotransact.catalog.domain.sizing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CustomValues;

/**
 * A size chart a seller wrote once and reuses on their products.
 *
 * @param ownerOrg        the seller organisation that owns it; only its own products can link it
 * @param unitSystem      the unit its values are written in; the other unit is shown by conversion
 * @param productTypeCode the product type it was made for; null = any
 * @param categoryId      the category node it was made for; null = any
 * @param fitNote         how the sizes fit (a product can say otherwise); null for no statement
 * @param howToMeasure    free text telling shoppers how to take their measurements; null for none
 * @param retiredAt       when the seller retired it; a retired chart stays on the products that use it but cannot be
 *                        linked to others
 */
public record SizeChart(UUID id, String ownerOrg, String name, MeasurementUnit unitSystem, String productTypeCode,
                        UUID categoryId, List<ChartColumn> columns, List<ChartRow> rows, FitNote fitNote,
                        String howToMeasure, Instant retiredAt, Instant createdAt, Instant updatedAt, long version) {

    public SizeChart {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerOrg, "ownerOrg");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitSystem, "unitSystem");
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public boolean retired() {
        return retiredAt != null;
    }

    /** The row of this size, ignoring case and spacing. */
    public Optional<ChartRow> row(String size) {
        String wanted = CustomValues.key(size);
        return rows.stream().filter(r -> CustomValues.key(r.size()).equals(wanted)).findFirst();
    }

    /** The same chart with new content. */
    public SizeChart replaced(ChartContent content, Instant now) {
        return new SizeChart(id, ownerOrg, content.name(), content.unitSystem(), content.productTypeCode(),
                content.categoryId(), content.columns(), content.rows(), content.fitNote(), content.howToMeasure(),
                retiredAt, createdAt, now, version);
    }

    /** The same chart, retired. */
    public SizeChart retire(Instant now) {
        return new SizeChart(id, ownerOrg, name, unitSystem, productTypeCode, categoryId, columns, rows, fitNote,
                howToMeasure, now, createdAt, now, version);
    }

    /** The content a seller can edit. */
    public ChartContent content() {
        return new ChartContent(name, unitSystem, productTypeCode, categoryId, columns, rows, fitNote, howToMeasure);
    }
}
