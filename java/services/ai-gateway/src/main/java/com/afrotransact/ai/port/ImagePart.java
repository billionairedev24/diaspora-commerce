package com.afrotransact.ai.port;

import java.util.Arrays;

/**
 * An image attached to a user message (vision models), sent inline to the provider.
 *
 * @param mimeType {@code image/jpeg}, {@code image/png} or {@code image/webp}
 * @param data     the image bytes
 */
public record ImagePart(String mimeType, byte[] data) {

    public ImagePart {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required");
        }
        data = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ImagePart other && mimeType.equals(other.mimeType) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return mimeType.hashCode() * 31 + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ImagePart[" + mimeType + ", " + data.length + " bytes]";
    }
}
