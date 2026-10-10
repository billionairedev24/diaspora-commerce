package com.afrotransact.ai.application;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.afrotransact.ai.domain.moderation.ImageDecision;
import com.afrotransact.ai.domain.moderation.ImageLabel;

/**
 * The AI moderation hook for uploaded images (called by media-service with its own service token before a product or
 * storefront image becomes public). Advisory: media combines it with its own rules and fails open or closed as
 * configured; staff release or reject held images.
 */
public interface ImageModerationService {

    Result moderate(Caller caller, Request request);

    /**
     * An image to check.
     *
     * @param image       image bytes (media sends a small JPEG preview)
     * @param contentType {@code image/jpeg}, {@code image/png} or {@code image/webp}
     * @param purpose     {@code product-image} or {@code storefront}
     */
    record Request(byte[] image, String contentType, String purpose) {

        public Request {
            image = image == null ? new byte[0] : image.clone();
        }

        @Override
        public byte[] image() {
            return image.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Request other && Arrays.equals(image, other.image)
                    && Objects.equals(contentType, other.contentType)
                    && Objects.equals(purpose, other.purpose);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(image);
        }

        @Override
        public String toString() {
            return "Request[" + contentType + ", " + image.length + " bytes, " + purpose + "]";
        }
    }

    /**
     * The verdict.
     *
     * @param decision ALLOW, REVIEW or BLOCK
     * @param labels   findings such as WATERMARK or WEAPONS
     * @param model    model that answered
     */
    record Result(ImageDecision decision, List<ImageLabel> labels, String model) {
    }
}
