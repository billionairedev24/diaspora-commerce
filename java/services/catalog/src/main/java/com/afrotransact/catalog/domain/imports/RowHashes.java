package com.afrotransact.catalog.domain.imports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The content hash of a row of an upload (S393): the normalised cells (trimmed, trailing blanks dropped) hashed with SHA-256.
 * A CREATE row whose SKU is already listed and whose hash equals the one stored when it was imported is the same row again:
 * it is skipped, not refused as a duplicate SKU.
 */
public final class RowHashes {

    private static final char SEPARATOR = '\u001f';

    private RowHashes() {
    }

    public static String of(List<String> cells) {
        int end = cells.size();
        while (end > 0 && (cells.get(end - 1) == null || cells.get(end - 1).isBlank())) {
            end--;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < end; i++) {
            text.append(cells.get(i) == null ? "" : cells.get(i).strip()).append(SEPARATOR);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
