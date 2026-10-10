package com.afrotransact.catalog.domain.barcode;

import java.util.List;
import java.util.Objects;

/**
 * An encoded barcode, ready for any renderer.
 *
 * @param symbology     the symbology
 * @param data          encoded data: the Code 128 text, the 13 EAN digits, or the GS1 element string with
 *                      {@code <GS>} separators
 * @param humanReadable text printed under the bars ({@code (01)…(10)…} for GS1-128)
 * @param modules       bar pattern, '1' = bar module, '0' = space module, without quiet zones
 * @param quietZone     quiet zone required on each side, in modules
 * @param codewords     Code 128 codewords (start, data, check; no stop) — empty for EAN-13
 */
public record BarcodeSymbol(Symbology symbology, String data, String humanReadable, String modules, int quietZone,
                            List<Integer> codewords) {

    public BarcodeSymbol {
        Objects.requireNonNull(symbology, "symbology");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(humanReadable, "humanReadable");
        Objects.requireNonNull(modules, "modules");
        codewords = codewords == null ? List.of() : List.copyOf(codewords);
    }

    /** Total width in modules, quiet zones included. */
    public int totalModules() {
        return modules.length() + 2 * quietZone;
    }

    /** Runs of equal modules from the left edge of the bars: even indexes are bars, odd indexes spaces. */
    public int[] runs() {
        int[] out = new int[modules.length()];
        int n = 0;
        int i = 0;
        while (i < modules.length()) {
            int j = i;
            while (j < modules.length() && modules.charAt(j) == modules.charAt(i)) {
                j++;
            }
            out[n++] = j - i;
            i = j;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
