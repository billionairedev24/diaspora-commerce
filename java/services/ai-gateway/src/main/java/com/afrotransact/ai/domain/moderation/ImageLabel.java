package com.afrotransact.ai.domain.moderation;

/**
 * A finding on an image with the decision it implies at minimum.
 *
 * @param code        label such as {@code WATERMARK}
 * @param description short explanation for the reviewer
 */
public record ImageLabel(String code, String description) {

    public ImageLabel {
        code = code == null ? "" : code;
        description = description == null ? "" : description;
    }
}
