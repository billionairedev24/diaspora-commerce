package com.afrotransact.catalog.domain.images;

import java.util.List;

/**
 * What media-service says about one published image for the slot it is used in. The pixel size, size in bytes and
 * type are media's own measurements; a client's numbers are never used.
 *
 * @param url         the image's public URL
 * @param found       false when media does not know the URL as a published image (it could not be checked)
 * @param verdict     {@code PASS}, {@code WARN}, {@code HOLD} or {@code REJECT}
 * @param checks      the findings (warnings and above, and recommendations)
 * @param measured    false for images stored before the checks existed: judged on their size only
 * @param assetId     the asset id when the image was asked for by id (a held photo has no URL); else null
 * @param status      with an asset id: {@code READY} (then {@code url} is its public URL), {@code HELD},
 *                    {@code REJECTED} or {@code UNKNOWN}; else null
 */
public record ImageCheckResult(String url, boolean found, String verdict, List<ImageFinding> checks, int width,
                               int height, long bytes, String contentType, boolean measured, String assetId,
                               String status) {

    public ImageCheckResult(String url, boolean found, String verdict, List<ImageFinding> checks, int width,
                            int height, long bytes, String contentType, boolean measured) {
        this(url, found, verdict, checks, width, height, bytes, contentType, measured, null, null);
    }

    public ImageCheckResult {
        verdict = verdict == null ? "REJECT" : verdict;
        checks = checks == null ? List.of() : List.copyOf(checks);
        contentType = contentType == null ? "" : contentType;
        url = url == null ? "" : url;
    }

    /** True when media holds the image (asked for by asset id) for staff review: not an error, the listing waits. */
    public boolean held() {
        return "HELD".equals(status);
    }

    /** True when the image was asked for by asset id and media published it: {@code url} is its public URL. */
    public boolean ready() {
        return "READY".equals(status) && !url.isBlank();
    }

    /** True when the image may not be used: rejected, held for staff, or not known to media. */
    public boolean blocked() {
        return !found || "REJECT".equals(verdict) || "HOLD".equals(verdict);
    }

    /** The plain message that tells the seller why, or a generic one. */
    public String message() {
        return checks.stream().filter(ImageFinding::blocks).map(ImageFinding::message).filter(m -> !m.isBlank())
                .findFirst().orElse("This photo cannot be used. Upload a different one.");
    }

    /** The result for a picture that could not be found at media-service. */
    public static ImageCheckResult unknown(String url) {
        return new ImageCheckResult(url, false, "REJECT", List.of(new ImageFinding(ImageFinding.NOT_VERIFIED,
                ImageFinding.REJECT, "We could not check this photo. Upload it again from your device or your image library.")),
                0, 0, 0, "", false);
    }
}
