package com.afrotransact.catalog.domain.barcode;

import java.util.ArrayList;
import java.util.List;

/**
 * Code 128 (ISO/IEC 15417) with code sets B and C and FNC1, as used for AfroTransact identifiers and GS1-128.
 * Digit runs switch to code set C (two digits per codeword) when that is shorter; everything else uses code set B
 * (ASCII 32–126). The bar widths match {@code design/at-barcode.js}.
 */
final class Code128 {

    /** Marker for FNC1 in the input sequence (GS1-128 start and separator after variable-length AIs). */
    static final int FNC1 = -1;

    static final int START_B = 104;
    static final int START_C = 105;
    static final int CODE_B = 100;
    static final int CODE_C = 99;
    static final int FNC1_VALUE = 102;
    static final int STOP = 106;

    /** Quiet zone on each side, in modules (ISO/IEC 15417: at least 10X). */
    static final int QUIET_ZONE = 10;

    private static final String[] WIDTHS = ("212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 "
            + "221312 231212 112232 122132 122231 113222 123122 123221 223211 221132 221231 213212 223112 312131 "
            + "311222 321122 321221 312212 322112 322211 212123 212321 232121 111323 131123 131321 112313 132113 "
            + "132311 211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 "
            + "213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 121124 "
            + "121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 "
            + "111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 412121 111143 111341 "
            + "131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 211214 211232 2331112")
            .split(" ");

    private Code128() {
    }

    /**
     * Codewords for {@code input} (characters, or {@link #FNC1}): start code, data, check symbol — without the stop
     * pattern.
     */
    static List<Integer> codewords(int[] input) {
        for (int c : input) {
            if (c != FNC1 && (c < 32 || c > 126)) {
                throw new IllegalArgumentException("Code 128 supports printable ASCII only (got U+"
                        + String.format("%04X", c) + ")");
            }
        }
        List<Integer> out = new ArrayList<>();
        int first = 0;
        while (first < input.length && input[first] == FNC1) {
            first++;
        }
        int lead = digitRun(input, first);
        boolean setC = lead >= 4 || lead >= 2 && first + lead == input.length && lead % 2 == 0;
        out.add(setC ? START_C : START_B);
        int i = 0;
        while (i < input.length) {
            int c = input[i];
            if (c == FNC1) {
                out.add(FNC1_VALUE);
                i++;
                continue;
            }
            int run = digitRun(input, i);
            if (setC) {
                if (run >= 2) {
                    out.add((input[i] - '0') * 10 + (input[i + 1] - '0'));
                    i += 2;
                } else {
                    out.add(CODE_B);
                    setC = false;
                }
                continue;
            }
            if (worthSwitchingToC(input, i, run)) {
                if (run % 2 == 1) {
                    out.add(input[i] - 32);
                    i++;
                }
                out.add(CODE_C);
                setC = true;
                continue;
            }
            out.add(c - 32);
            i++;
        }
        int sum = out.getFirst();
        for (int k = 1; k < out.size(); k++) {
            sum += out.get(k) * k;
        }
        out.add(sum % 103);
        return List.copyOf(out);
    }

    /** Module pattern ('1' bar, '0' space) of the codewords followed by the stop pattern, without quiet zones. */
    static String modules(List<Integer> codewords) {
        StringBuilder sb = new StringBuilder();
        for (int cw : codewords) {
            append(sb, WIDTHS[cw]);
        }
        append(sb, WIDTHS[STOP]);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String widths) {
        for (int k = 0; k < widths.length(); k++) {
            sb.repeat(k % 2 == 0 ? '1' : '0', widths.charAt(k) - '0');
        }
    }

    /** Four or more digits at the end (or before an FNC1), or six or more anywhere, are cheaper in code set C. */
    private static boolean worthSwitchingToC(int[] input, int at, int run) {
        if (run >= 6) {
            return true;
        }
        int end = at + run;
        return run >= 4 && (end == input.length || input[end] == FNC1);
    }

    private static int digitRun(int[] input, int from) {
        int n = 0;
        while (from + n < input.length && input[from + n] >= '0' && input[from + n] <= '9') {
            n++;
        }
        return n;
    }
}
