package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * Everything a template file shows.
 *
 * @param title        e.g. "Women's dresses" or "All categories"
 * @param fileStem     file name without extension
 * @param instructions how-to lines for the Instructions sheet (and CSV comment lines)
 * @param examples     example rows, cells in column order
 */
public record TemplateSpec(String title, String fileStem, List<ImportColumn> columns, List<String> instructions,
                           List<List<String>> examples) {

    public TemplateSpec {
        columns = List.copyOf(columns);
        instructions = List.copyOf(instructions);
        examples = List.copyOf(examples);
    }
}
