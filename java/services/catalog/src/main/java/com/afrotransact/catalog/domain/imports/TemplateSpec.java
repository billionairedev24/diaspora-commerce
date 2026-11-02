package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * Everything a template file shows.
 *
 * @param title        e.g. "Women's dresses" or "All categories"
 * @param fileStem     file name without extension
 * @param instructions how-to lines for the Instructions sheet (and CSV comment lines)
 * @param examples     example rows, cells in column order
 * @param sheetName    the sheet of an .xlsx template that holds the header and the rows to fill
 */
public record TemplateSpec(String title, String fileStem, List<ImportColumn> columns, List<String> instructions,
                           List<List<String>> examples, String sheetName) {

    /** The product template: rows go on the "Products" sheet. */
    public TemplateSpec(String title, String fileStem, List<ImportColumn> columns, List<String> instructions,
                        List<List<String>> examples) {
        this(title, fileStem, columns, instructions, examples, "Products");
    }

    public TemplateSpec {
        columns = List.copyOf(columns);
        instructions = List.copyOf(instructions);
        examples = List.copyOf(examples);
    }
}
