package com.afrotransact.ai.domain.moderation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a vision model's answer into a moderation verdict the platform can trust. Labels come from a fixed vocabulary
 * (unknown ones become the informational {@code OTHER}); each label implies a minimum decision, so a model that names
 * prohibited content but answers {@code ALLOW} still blocks, and an answer that can't be read (or an unknown decision)
 * is sent to review rather than allowed.
 */
public final class ImageModerationPolicy {

    /** Prohibited content: blocks the image. */
    public static final Set<String> BLOCKING = Set.of("NUDITY", "SEXUAL", "VIOLENCE", "GORE", "HATE", "WEAPONS",
            "DRUGS", "ILLEGAL");

    /** Marketplace rules a person should check: holds the image for review. */
    public static final Set<String> REVIEWABLE = Set.of("WATERMARK", "CONTACT_INFO", "TEXT_OVERLAY", "NOT_A_PRODUCT",
            "THIRD_PARTY_LOGO", "MISLEADING");

    /** Informational only (does not change the model's decision); labels outside the vocabulary become OTHER. */
    public static final Set<String> INFORMATIONAL = Set.of("LOW_QUALITY", "NON_WHITE_BACKGROUND", "OTHER");

    /** Label used when the model's answer could not be read. */
    public static final String UNREADABLE = "UNREADABLE_ANSWER";

    private ImageModerationPolicy() {
    }

    /**
     * The verdict for a model's raw decision and labels.
     *
     * @param modelDecision the model's {@code decision} field (any case; null when the answer was unreadable)
     * @param labels        the model's labels in order
     */
    public static Verdict decide(String modelDecision, List<ImageLabel> labels) {
        ImageDecision decision;
        List<ImageLabel> out = new ArrayList<>();
        if (modelDecision == null) {
            decision = ImageDecision.REVIEW;
            out.add(new ImageLabel(UNREADABLE, "The model's answer could not be read"));
        } else {
            decision = parse(modelDecision);
        }
        Map<String, ImageLabel> unique = new LinkedHashMap<>();
        for (ImageLabel l : labels) {
            String code = normalise(l.code());
            if (!code.isEmpty()) {
                unique.putIfAbsent(code, new ImageLabel(code, l.description().trim()));
            }
        }
        for (ImageLabel l : unique.values()) {
            decision = decision.atLeast(implied(l.code()));
            out.add(l);
        }
        return new Verdict(decision, List.copyOf(out));
    }

    private static ImageDecision parse(String raw) {
        try {
            return ImageDecision.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ImageDecision.REVIEW;
        }
    }

    private static String normalise(String code) {
        String c = code.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (c.isEmpty()) {
            return c;
        }
        if (BLOCKING.contains(c) || REVIEWABLE.contains(c) || INFORMATIONAL.contains(c)) {
            return c;
        }
        return "OTHER";
    }

    private static ImageDecision implied(String code) {
        if (BLOCKING.contains(code)) {
            return ImageDecision.BLOCK;
        }
        if (REVIEWABLE.contains(code)) {
            return ImageDecision.REVIEW;
        }
        return ImageDecision.ALLOW;
    }

    /**
     * A moderation verdict.
     *
     * @param decision the final decision
     * @param labels   normalised labels (deduplicated, vocabulary only)
     */
    public record Verdict(ImageDecision decision, List<ImageLabel> labels) {
    }
}
